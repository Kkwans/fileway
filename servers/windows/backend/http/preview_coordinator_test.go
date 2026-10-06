package fbhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"image/png"
	"io"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/img"
	"github.com/spf13/afero"
)

func TestPreviewCoordinatorMergesConcurrentWork(t *testing.T) {
	coordinator := newPreviewCoordinator()
	gate := make(chan struct{})
	workStarted := make(chan struct{})
	var calls atomic.Int32
	var signalWorkStarted sync.Once

	const waiters = 12
	results := make(chan []byte, waiters)
	errorsFound := make(chan error, waiters)
	var group sync.WaitGroup
	for range waiters {
		group.Add(1)
		go func() {
			defer group.Done()
			result, err := coordinator.Do(context.Background(), "same-key", func(context.Context) ([]byte, error) {
				calls.Add(1)
				signalWorkStarted.Do(func() { close(workStarted) })
				<-gate
				return []byte("preview"), nil
			})
			results <- result
			errorsFound <- err
		}()
	}

	select {
	case <-workStarted:
	case <-time.After(time.Second):
		close(gate)
		group.Wait()
		t.Fatal("shared work did not start")
	}

	deadline := time.Now().Add(time.Second)
	for {
		coordinator.Lock()
		flight := coordinator.flights["same-key"]
		allWaitersRegistered := flight != nil && flight.waiters == waiters
		coordinator.Unlock()
		if allWaitersRegistered {
			break
		}
		if time.Now().After(deadline) {
			close(gate)
			group.Wait()
			t.Fatalf("registered waiters did not reach %d", waiters)
		}
		time.Sleep(time.Millisecond)
	}
	close(gate)
	group.Wait()
	close(results)
	close(errorsFound)

	if calls.Load() != 1 {
		t.Fatalf("work called %d times, want 1", calls.Load())
	}
	for err := range errorsFound {
		if err != nil {
			t.Fatal(err)
		}
	}
	for result := range results {
		if string(result) != "preview" {
			t.Fatalf("result = %q", result)
		}
	}
}

func TestPreviewCoordinatorCancelsUnobservedWorkAndCoolsFailures(t *testing.T) {
	coordinator := newPreviewCoordinator()
	coordinator.cooldown = 20 * time.Millisecond

	ctx, cancel := context.WithCancel(context.Background())
	workCanceled := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		_, err := coordinator.Do(ctx, "cancel", func(workCtx context.Context) ([]byte, error) {
			<-workCtx.Done()
			close(workCanceled)
			return nil, context.Cause(workCtx)
		})
		done <- err
	}()
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter error = %v", err)
	}
	select {
	case <-workCanceled:
	case <-time.After(time.Second):
		t.Fatal("shared work was not canceled after its last waiter left")
	}

	boom := errors.New("boom")
	if _, err := coordinator.Do(context.Background(), "failure", func(context.Context) ([]byte, error) {
		return nil, boom
	}); !errors.Is(err, boom) {
		t.Fatalf("first error = %v", err)
	}
	if _, err := coordinator.Do(context.Background(), "failure", func(context.Context) ([]byte, error) {
		return []byte("unexpected"), nil
	}); !errors.Is(err, errPreviewCoolingDown) {
		t.Fatalf("cooldown error = %v", err)
	}
	time.Sleep(25 * time.Millisecond)
	result, err := coordinator.Do(context.Background(), "failure", func(context.Context) ([]byte, error) {
		return []byte("recovered"), nil
	})
	if err != nil || string(result) != "recovered" {
		t.Fatalf("recovery result = %q, error = %v", result, err)
	}
}

func TestPreviewCoordinatorKeepsWarmupWorkAfterLastWaiterLeaves(t *testing.T) {
	coordinator := newPreviewCoordinator()
	workStarted := make(chan struct{})
	workFinished := make(chan struct{})
	workCanceled := make(chan struct{})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := coordinator.DoKeepAlive(ctx, "thumb-key", func(workCtx context.Context) ([]byte, error) {
			close(workStarted)
			select {
			case <-workCtx.Done():
				close(workCanceled)
				return nil, context.Cause(workCtx)
			case <-time.After(20 * time.Millisecond):
				close(workFinished)
				return []byte("warmed"), nil
			}
		})
		done <- err
	}()

	<-workStarted
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter error = %v", err)
	}
	select {
	case <-workFinished:
	case <-time.After(time.Second):
		t.Fatal("warmup work did not finish after its last waiter left")
	}
	select {
	case <-workCanceled:
		t.Fatal("warmup work was canceled after its last waiter left")
	default:
	}
}

func TestFFmpegPreviewWorkerBounds(t *testing.T) {
	if got := cap(newFFmpegPreviewService(0).workers); got != 1 {
		t.Fatalf("default worker count = %d, want 1", got)
	}
	if got := cap(newFFmpegPreviewService(99).workers); got != 2 {
		t.Fatalf("maximum worker count = %d, want 2", got)
	}
}

func TestFFmpegImagePreviewWorkerBoundsAndPolicy(t *testing.T) {
	if got := cap(newFFmpegImagePreviewService(0).workers); got != 1 {
		t.Fatalf("default image worker count = %d, want 1", got)
	}
	if got := cap(newFFmpegImagePreviewService(99).workers); got != 1 {
		t.Fatalf("image worker count must stay globally bounded at 1, got %d", got)
	}

	largeJPEG := &files.FileInfo{Extension: ".jpg", Size: ffmpegImagePreviewMinBytes}
	if !shouldUseFFmpegImagePreview(largeJPEG, PreviewSizeBig) {
		t.Fatal("large JPEG big preview should use FFmpeg")
	}
	if !shouldUseFFmpegImagePreview(largeJPEG, PreviewSizeThumb) {
		t.Fatal("large JPEG thumbnail should use FFmpeg")
	}
	for _, file := range []*files.FileInfo{
		{Extension: ".png", Size: ffmpegImagePreviewMinBytes},
		{Extension: ".jpg", Size: ffmpegImagePreviewMinBytes - 1},
		{Extension: ".jpg", Size: ffmpegImagePreviewMinBytes},
	} {
		if file.Extension == ".jpg" && file.Size == ffmpegImagePreviewMinBytes {
			continue
		}
		if shouldUseFFmpegImagePreview(file, PreviewSizeBig) {
			t.Fatalf("FFmpeg image policy unexpectedly selected %+v", file)
		}
	}
}

func TestLargeJPEGThumbnailsPreferNativeImagePipeline(t *testing.T) {
	largeJPEG := &files.FileInfo{Extension: ".jpg", Size: ffmpegImagePreviewMinBytes}
	if !shouldPreferNativeImagePreview(largeJPEG, PreviewSizeThumb) {
		t.Fatal("large JPEG thumbnails should prefer the native image pipeline")
	}
	if !shouldPreferNativeImagePreview(largeJPEG, PreviewSizeBig) {
		t.Fatal("moderate large JPEG big previews should prefer the native image pipeline")
	}
	oversizedJPEG := &files.FileInfo{
		Extension: ".jpg",
		Size:      nativeImagePreviewMaxBytes + 1,
	}
	if shouldPreferNativeImagePreview(oversizedJPEG, PreviewSizeBig) {
		t.Fatal("oversized JPEG big previews should keep the bounded FFmpeg path")
	}
}

func TestFFmpegImageFilterPreservesPreviewGeometry(t *testing.T) {
	filter, quality, err := ffmpegImageFilter(PreviewSizeBig)
	if err != nil || filter != "scale=1080:1080:force_original_aspect_ratio=decrease" || quality != "3" {
		t.Fatalf("big filter = %q, quality = %q, error = %v", filter, quality, err)
	}
	filter, quality, err = ffmpegImageFilter(PreviewSizeThumb)
	if err != nil || filter != "scale=256:256:force_original_aspect_ratio=increase:flags=fast_bilinear,crop=256:256" || quality != "5" {
		t.Fatalf("thumb filter = %q, quality = %q, error = %v", filter, quality, err)
	}
}

func TestFFmpegImageArgsUseTwoInternalThreadsWithoutRaisingTaskConcurrency(t *testing.T) {
	args := ffmpegImageArgs("/source.jpg", "scale=256:256", "5")
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{"-threads\x002", "-filter_threads\x002"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("image preview args missing %q: %q", expected, joined)
		}
	}
}

func TestLargeJPEGThumbnailWarmHintRequiresExplicitRequest(t *testing.T) {
	largeJPEG := &files.FileInfo{Extension: ".jpg", Size: ffmpegImagePreviewMinBytes}
	withoutHint := httptest.NewRequest("GET", "/api/preview/thumb/photos/large.jpg", nil)
	withHint := httptest.NewRequest("GET", "/api/preview/thumb/photos/large.jpg?warm=big", nil)

	if shouldWarmLargeJPEGPreview(withoutHint, largeJPEG) {
		t.Fatal("thumbnail requests without warm hint must not generate a full preview")
	}
	if !shouldWarmLargeJPEGPreview(withHint, largeJPEG) {
		t.Fatal("explicit warm hint should enable the large JPEG preview bundle")
	}
}

func TestLargeJPEGThumbnailWarmHintStoresBigAndThumbFromOneSourceDecode(t *testing.T) {
	file := &files.FileInfo{Path: "/photos/large.jpg", Extension: ".jpg", Size: ffmpegImagePreviewMinBytes, ModTime: time.Unix(10, 0)}
	cache := newMemoryPreviewCache()
	generator := &fakeImagePreviewGenerator{big: []byte("big-preview")}
	imgService := fakeImgService{}

	thumbnail, err := createLargeJPEGThumbnailWarmup(context.Background(), imgService, generator, cache, file)
	if err != nil {
		t.Fatal(err)
	}
	if string(thumbnail) != "big-preview" {
		t.Fatalf("warm thumbnail response = %q, want reusable big preview", thumbnail)
	}
	if generator.bigCalls != 1 {
		t.Fatalf("source decode calls = %d, want 1", generator.bigCalls)
	}
	if got, ok, _ := cache.Load(context.Background(), previewCacheKey(file, PreviewSizeBig)); !ok || string(got) != "big-preview" {
		t.Fatalf("big cache = %q, exists=%t", got, ok)
	}
	if got, ok, _ := cache.Load(context.Background(), previewCacheKey(file, PreviewSizeThumb)); !ok || string(got) != "thumb-preview" {
		t.Fatalf("thumb cache = %q, exists=%t", got, ok)
	}
}

func TestLargeJPEGWarmHintSharesInFlightBigPreviewWithRegularRequest(t *testing.T) {
	fs := afero.NewMemMapFs()
	if err := afero.WriteFile(fs, "/photos/large.jpg", []byte("fixture"), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	file := &files.FileInfo{
		Fs:        fs,
		Path:      "/photos/large.jpg",
		Name:      "large.jpg",
		Extension: ".jpg",
		Size:      ffmpegImagePreviewMinBytes,
		ModTime:   time.Unix(10, 0),
	}
	cache := newMemoryPreviewCache()
	coordinator := newPreviewCoordinator()
	service := &blockingPreviewImageService{
		bigStarted: make(chan struct{}),
		releaseBig: make(chan struct{}),
	}

	warmResponse := httptest.NewRecorder()
	warmDone := make(chan error, 1)
	go func() {
		_, err := handleImagePreview(
			warmResponse,
			httptest.NewRequest("GET", "/api/preview/thumb/photos/large.jpg?warm=big", nil),
			service,
			nil,
			cache,
			coordinator,
			file,
			PreviewSizeThumb,
			true,
			true,
		)
		warmDone <- err
	}()

	select {
	case <-service.bigStarted:
	case <-time.After(time.Second):
		t.Fatal("warm request did not start the large preview")
	}

	regularResponse := httptest.NewRecorder()
	regularDone := make(chan error, 1)
	go func() {
		_, err := handleImagePreview(
			regularResponse,
			httptest.NewRequest("GET", "/api/preview/big/photos/large.jpg", nil),
			service,
			nil,
			cache,
			coordinator,
			file,
			PreviewSizeBig,
			true,
			true,
		)
		regularDone <- err
	}()

	deadline := time.Now().Add(time.Second)
	for {
		coordinator.Lock()
		flight := coordinator.flights[previewCacheKey(file, PreviewSizeBig)]
		waiters := 0
		if flight != nil {
			waiters = flight.waiters
		}
		coordinator.Unlock()
		if waiters == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("large preview waiters = %d, want 2", waiters)
		}
		time.Sleep(time.Millisecond)
	}
	close(service.releaseBig)
	if err := <-warmDone; err != nil {
		t.Fatalf("warm request error: %v", err)
	}
	if err := <-regularDone; err != nil {
		t.Fatalf("regular request error: %v", err)
	}
	if got := service.bigCalls(); got != 1 {
		t.Fatalf("large preview resize calls = %d, want 1", got)
	}
}

func TestLargeJPEGWarmHintBypassesExistingListingThumbnail(t *testing.T) {
	file := &files.FileInfo{
		Path:      "/photos/large.jpg",
		Name:      "large.jpg",
		Extension: ".jpg",
		Size:      ffmpegImagePreviewMinBytes,
		ModTime:   time.Unix(10, 0),
	}
	cache := newMemoryPreviewCache()
	var staleThumbnail bytes.Buffer
	if err := png.Encode(&staleThumbnail, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	if err := cache.Store(context.Background(), previewCacheKey(file, PreviewSizeThumb), staleThumbnail.Bytes()); err != nil {
		t.Fatal(err)
	}
	var bigPreview bytes.Buffer
	if err := png.Encode(&bigPreview, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	if err := cache.Store(context.Background(), previewCacheKey(file, PreviewSizeBig), bigPreview.Bytes()); err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest("GET", "/api/preview/thumb/photos/large.jpg?warm=big", nil)
	response := httptest.NewRecorder()
	status, err := handleImagePreview(
		response,
		req,
		fakeImgService{},
		nil,
		cache,
		newPreviewCoordinator(),
		file,
		PreviewSizeThumb,
		true,
		true,
	)
	if err != nil || status != 0 {
		t.Fatalf("warm preview status = %d, error = %v", status, err)
	}
	if got := response.Body.Bytes(); !bytes.Equal(got, bigPreview.Bytes()) {
		t.Fatalf("warm preview response did not reuse the cached big preview")
	}
	if got := response.Header().Get("Cache-Control"); got != previewCacheControl {
		t.Fatalf("image preview cache control = %q, want %q", got, previewCacheControl)
	}
}

func TestVideoPreviewAdvertisesPrivateIdentityCache(t *testing.T) {
	file := &files.FileInfo{
		Path:      "/videos/clip.mp4",
		Name:      "clip.mp4",
		Extension: ".mp4",
		Size:      42,
		ModTime:   time.Unix(20, 0),
	}
	cache := newMemoryPreviewCache()
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	if err := cache.Store(context.Background(), previewCacheKey(file, PreviewSizeThumb), encoded.Bytes()); err != nil {
		t.Fatal(err)
	}

	response := httptest.NewRecorder()
	status, err := handleVideoPreview(
		response,
		httptest.NewRequest("GET", "/api/preview/thumb/videos/clip.mp4", nil),
		cache,
		newPreviewCoordinator(),
		nil,
		file,
		PreviewSizeThumb,
	)
	if err != nil || status != 0 {
		t.Fatalf("video preview status = %d, error = %v", status, err)
	}
	if got := response.Header().Get("Cache-Control"); got != previewCacheControl {
		t.Fatalf("video preview cache control = %q, want %q", got, previewCacheControl)
	}
}

func TestPreviewCoordinatorBoundsFailureCooldownEntries(t *testing.T) {
	coordinator := newPreviewCoordinator()
	coordinator.Lock()
	for index := 0; index < previewFailureEntries+10; index++ {
		coordinator.failures[fmt.Sprintf("failed-%d", index)] = time.Now().Add(time.Minute)
		if len(coordinator.failures) > previewFailureEntries {
			coordinator.evictEarliestFailure()
		}
	}
	count := len(coordinator.failures)
	coordinator.Unlock()
	if count != previewFailureEntries {
		t.Fatalf("failure cooldown entries = %d, want %d", count, previewFailureEntries)
	}
}

func TestPreviewCacheKeyIncludesFileIdentityAndSpecification(t *testing.T) {
	base := &files.FileInfo{Path: "/photos/image.jpg", Size: 100, ModTime: time.Unix(10, 20)}
	original := previewCacheKey(base, PreviewSizeThumb)

	changedSize := *base
	changedSize.Size++
	changedTime := *base
	changedTime.ModTime = changedTime.ModTime.Add(time.Nanosecond)
	if original == previewCacheKey(&changedSize, PreviewSizeThumb) {
		t.Fatal("file size must invalidate a cached preview")
	}
	if original == previewCacheKey(&changedTime, PreviewSizeThumb) {
		t.Fatal("mtime must invalidate a cached preview")
	}
	if original == previewCacheKey(base, PreviewSizeBig) {
		t.Fatal("preview specification must be part of the cache key")
	}
}

func TestValidCachedPreviewRejectsCorruptData(t *testing.T) {
	if validCachedPreview([]byte("not an image")) {
		t.Fatal("corrupt cache data must be rejected")
	}
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	if !validCachedPreview(encoded.Bytes()) {
		t.Fatal("valid image data should be reusable")
	}
}

func TestFFmpegPreviewFailsSafelyWhenBinaryIsMissing(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	_, err := newFFmpegPreviewService(1).create(context.Background(), &files.FileInfo{})
	if err == nil {
		t.Fatal("missing FFmpeg should return a safe error")
	}
}

type fakeImagePreviewGenerator struct {
	big      []byte
	bigCalls int
}

type blockingPreviewImageService struct {
	mu         sync.Mutex
	bigCount   int
	bigStarted chan struct{}
	releaseBig chan struct{}
	startOnce  sync.Once
}

func (s *blockingPreviewImageService) FormatFromExtension(string) (img.Format, error) {
	return img.FormatJpeg, nil
}

func (s *blockingPreviewImageService) Resize(_ context.Context, _ io.Reader, width, _ int, out io.Writer, _ ...img.Option) error {
	if width == 1080 {
		s.mu.Lock()
		s.bigCount++
		s.mu.Unlock()
		s.startOnce.Do(func() { close(s.bigStarted) })
		<-s.releaseBig
		_, err := out.Write([]byte("big-preview"))
		return err
	}
	_, err := out.Write([]byte("thumb-preview"))
	return err
}

func (s *blockingPreviewImageService) bigCalls() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.bigCount
}

func (g *fakeImagePreviewGenerator) create(context.Context, *files.FileInfo, PreviewSize) ([]byte, error) {
	g.bigCalls++
	return append([]byte(nil), g.big...), nil
}

type fakeImgService struct{}

func (fakeImgService) FormatFromExtension(string) (img.Format, error) {
	return img.FormatJpeg, nil
}

func (fakeImgService) Resize(_ context.Context, _ io.Reader, _, _ int, out io.Writer, _ ...img.Option) error {
	_, err := out.Write([]byte("thumb-preview"))
	return err
}

type memoryPreviewCache struct {
	mu     sync.RWMutex
	values map[string][]byte
}

func newMemoryPreviewCache() *memoryPreviewCache {
	return &memoryPreviewCache{values: make(map[string][]byte)}
}

func (c *memoryPreviewCache) Store(_ context.Context, key string, value []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.values[key] = append([]byte(nil), value...)
	return nil
}

func (c *memoryPreviewCache) Load(_ context.Context, key string) ([]byte, bool, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	value, ok := c.values[key]
	return append([]byte(nil), value...), ok, nil
}

func (c *memoryPreviewCache) Delete(_ context.Context, key string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.values, key)
	return nil
}
