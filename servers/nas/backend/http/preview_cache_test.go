package fbhttp

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/img"
)

type failingPreviewCache struct {
	loadErr   error
	storeErr  error
	deleteErr error
}

func (cache failingPreviewCache) Store(context.Context, string, []byte) error {
	return cache.storeErr
}

func (cache failingPreviewCache) Load(context.Context, string) ([]byte, bool, error) {
	return nil, false, cache.loadErr
}

func (cache failingPreviewCache) Delete(context.Context, string) error {
	return cache.deleteErr
}

type previewImageService struct{}

func (previewImageService) FormatFromExtension(string) (img.Format, error) {
	return img.FormatJpeg, nil
}

func (previewImageService) Resize(_ context.Context, _ io.Reader, _, _ int, out io.Writer, _ ...img.Option) error {
	_, err := out.Write([]byte("generated preview"))
	return err
}

func TestPreviewCacheLoadFailureFallsBackToGeneration(t *testing.T) {
	value, exists, err := loadPreviewCache(context.Background(), failingPreviewCache{loadErr: errors.New("cache offline")}, "key")
	if err != nil || exists || value != nil {
		t.Fatalf("value = %q, exists = %v, error = %v", value, exists, err)
	}
}

func TestPreviewCacheCancellationStillStopsRequest(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, _, err := loadPreviewCache(ctx, failingPreviewCache{loadErr: errors.New("cache offline")}, "key")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v, want context.Canceled", err)
	}
}

func TestPreviewStoreFailureDoesNotDiscardGeneratedImage(t *testing.T) {
	fs := afero.NewMemMapFs()
	if err := afero.WriteFile(fs, "/image.jpg", []byte("source"), 0600); err != nil {
		t.Fatal(err)
	}
	file := &files.FileInfo{Fs: fs, Path: "/image.jpg", Name: "image.jpg", Extension: ".jpg", ModTime: time.Now()}
	generated, err := createPreview(context.Background(), previewImageService{}, failingPreviewCache{storeErr: errors.New("disk full")}, file, PreviewSizeBig)
	if err != nil || string(generated) != "generated preview" {
		t.Fatalf("generated = %q, error = %v", generated, err)
	}
}

func TestThumbnailDeleteFailureDoesNotBlockFileOperation(t *testing.T) {
	file := &files.FileInfo{Path: "/photos/image.jpg", ModTime: time.Now()}
	if err := delThumbs(context.Background(), failingPreviewCache{deleteErr: errors.New("cache read only")}, file); err != nil {
		t.Fatalf("thumbnail cleanup error = %v", err)
	}
}
