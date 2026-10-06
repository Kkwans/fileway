package hls

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestReserveMergesSameSourceAndRunCreatesReusableCache(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	input := Input{UserID: 7, Path: "/电影/示例.mkv", Identity: "12:34", SourcePath: "/source.mkv", DurationSeconds: 20.042}
	var starts atomic.Int32
	var job Job
	start := func(candidate Job) (string, error) {
		starts.Add(1)
		job = candidate
		return "task-one", nil
	}
	first, created, err := service.Reserve(input, start)
	if err != nil || !created {
		t.Fatalf("first reserve = %#v, %v, %v", first, created, err)
	}
	if first.DurationSeconds != input.DurationSeconds || job.DurationSeconds != input.DurationSeconds {
		t.Fatalf("duration was not carried into cache job: status=%#v job=%#v", first, job)
	}
	second, created, err := service.Reserve(input, start)
	if err != nil || created || second.ID != first.ID || starts.Load() != 1 {
		t.Fatalf("merged reserve = %#v, %v, %v, starts=%d", second, created, err, starts.Load())
	}
	if err := service.Run(context.Background(), job); err != nil {
		t.Fatal(err)
	}
	completed, err := service.Get(first.ID, input.UserID)
	if err != nil || completed.State != StateCompleted || completed.SizeBytes <= 0 {
		t.Fatalf("completed = %#v, %v", completed, err)
	}
	if _, _, err := service.Asset(first.ID, input.UserID+1, "index.m3u8"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("cross-user asset error = %v", err)
	}
	if _, _, err := service.Asset(first.ID, input.UserID, "../meta.json"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unsafe asset error = %v", err)
	}

	reloaded, err := New(Config{CacheDir: service.cacheDir, MaxBytes: DefaultMaxBytes, Workers: 1, FFmpegPath: service.ffmpegPath})
	if err != nil {
		t.Fatal(err)
	}
	status, err := reloaded.Get(first.ID, input.UserID)
	if err != nil || status.State != StateCompleted {
		t.Fatalf("reloaded = %#v, %v", status, err)
	}
}

func TestWorkerLimitQueuesAndCancellationDoesNotRun(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 350*time.Millisecond)
	jobs := reserveJobs(t, service, 2)
	var wait sync.WaitGroup
	wait.Add(1)
	go func() {
		defer wait.Done()
		if err := service.Run(context.Background(), jobs[0]); err != nil {
			t.Errorf("first run: %v", err)
		}
	}()
	waitForState(t, service, jobs[0], StatePreparing)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := service.Run(ctx, jobs[1]); !errors.Is(err, context.Canceled) {
		t.Fatalf("queued cancellation = %v", err)
	}
	status, err := service.Get(jobs[1].ID, jobs[1].UserID)
	if err != nil || status.State != StateCanceled {
		t.Fatalf("canceled status = %#v, %v", status, err)
	}
	wait.Wait()
}

func TestLRUEvictionProtectsRecentlyPlayedEntry(t *testing.T) {
	service := newFakeService(t, 1, 1, 0)
	jobs := reserveJobs(t, service, 3)
	if err := service.Run(context.Background(), jobs[0]); err != nil {
		t.Fatal(err)
	}
	if _, _, err := service.Asset(jobs[0].ID, jobs[0].UserID, "index.m3u8"); err != nil {
		t.Fatal(err)
	}
	if err := service.Run(context.Background(), jobs[1]); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Get(jobs[0].ID, jobs[0].UserID); err != nil {
		t.Fatalf("playing entry was evicted: %v", err)
	}

	service.mu.Lock()
	service.entries[jobs[0].ID].leaseUntil = time.Time{}
	service.mu.Unlock()
	if err := service.Run(context.Background(), jobs[2]); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Get(jobs[0].ID, jobs[0].UserID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("old unprotected entry error = %v", err)
	}
	if _, err := service.Get(jobs[2].ID, jobs[2].UserID); err != nil {
		t.Fatalf("new entry was evicted: %v", err)
	}
}

func TestCappedBufferAcceptsAllInputAndRetainsOnlyLimit(t *testing.T) {
	buffer := cappedBuffer{limit: 4}
	written, err := buffer.Write([]byte("123456789"))
	if err != nil || written != 9 || buffer.String() != "1234" {
		t.Fatalf("buffer = %q written=%d err=%v", buffer.String(), written, err)
	}
}

func TestFFmpegArgsPadOddVideoDimensionsForYUV420(t *testing.T) {
	args := ffmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8", 1280)
	joined := strings.Join(args, "\x00")
	if !strings.Contains(joined, "pad=ceil(iw/2)*2:ceil(ih/2)*2") {
		t.Fatalf("ffmpeg filter does not pad odd dimensions: %q", joined)
	}
}

func TestFFmpegArgsExposeGrowingPlaylistAsSeekableEvent(t *testing.T) {
	args := ffmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8", 1280)
	joined := strings.Join(args, "\x00")
	if !strings.Contains(joined, "-hls_playlist_type\x00event") {
		t.Fatalf("growing HLS playlist is not marked as an event: %q", joined)
	}
}

func TestWebMArgsProduceBrowserSeekableCompatibilityFile(t *testing.T) {
	args := webMArgs("/source.mkv", "/tmp/index.webm.tmp")
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{"libvpx-vp9", "libopus", "-progress\x00pipe:1", "-threads\x002", "-f\x00webm", "/tmp/index.webm.tmp"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("WebM args missing %q: %q", expected, joined)
		}
	}
}

func TestWebMCopyArgsRemuxNativeStreams(t *testing.T) {
	args := webMCopyArgs("/source.mkv", "/tmp/index.webm.tmp")
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{"-c\x00copy", "-f\x00webm", "/tmp/index.webm.tmp"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("WebM copy args missing %q: %q", expected, joined)
		}
	}
}

func TestMP4CopyArgsRemuxBrowserCompatibleStreams(t *testing.T) {
	args := mp4CopyArgs("/source.mkv", "/tmp/index.mp4.tmp")
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{
		"-c\x00copy",
		"-movflags\x00+faststart",
		"-f\x00mp4",
		"/tmp/index.mp4.tmp",
	} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("MP4 copy args missing %q: %q", expected, joined)
		}
	}
	if strings.Contains(joined, "libx264") || strings.Contains(joined, "libvpx-vp9") {
		t.Fatalf("MP4 copy args unexpectedly re-encode video: %q", joined)
	}
}

func TestParseFFmpegProgressLineReportsOnlyOutputTime(t *testing.T) {
	seconds, ok := parseFFmpegProgressLine("out_time_ms=2500000")
	if !ok || seconds != 2.5 {
		t.Fatalf("parsed progress = %v, %v", seconds, ok)
	}
	if _, ok := parseFFmpegProgressLine("progress=end"); ok {
		t.Fatal("non-duration progress line unexpectedly parsed")
	}
	if _, ok := parseFFmpegProgressLine("out_time_ms=-1"); ok {
		t.Fatal("negative progress unexpectedly parsed")
	}
}

func TestCopyFFmpegArgsKeepBrowserCompatibleStreams(t *testing.T) {
	args := copyFFmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8")
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{
		"-c:v\x00copy",
		"-c:a\x00copy",
		"-hls_playlist_type\x00event",
	} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("copy HLS args missing %q: %q", expected, joined)
		}
	}
	if strings.Contains(joined, "libx264") || strings.Contains(joined, "libvpx-vp9") {
		t.Fatalf("copy HLS args unexpectedly re-encode video: %q", joined)
	}
}

func TestCanCopyMediaRequiresBrowserCompatibleCodecs(t *testing.T) {
	tests := []struct {
		name       string
		videoCodec string
		audioCodec string
		want       bool
	}{
		{name: "h264 aac", videoCodec: "h264", audioCodec: "aac", want: true},
		{name: "h264 no audio", videoCodec: "h264", want: true},
		{name: "hevc aac", videoCodec: "hevc", audioCodec: "aac", want: false},
		{name: "h264 dts", videoCodec: "h264", audioCodec: "dts", want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := CanCopyMedia(test.videoCodec, test.audioCodec); got != test.want {
				t.Fatalf("CanCopyMedia(%q, %q) = %v, want %v", test.videoCodec, test.audioCodec, got, test.want)
			}
		})
	}
}

func TestCanCopyMediaRejectsH264ProfilesAndPixelFormatsBrowsersCannotDecode(t *testing.T) {
	tests := []struct {
		name        string
		videoCodec  string
		audioCodec  string
		pixelFormat string
		profile     string
		bitDepth    int
		want        bool
	}{
		{name: "ordinary eight bit 420", videoCodec: "h264", audioCodec: "aac", pixelFormat: "yuv420p", profile: "High", bitDepth: 8, want: true},
		{name: "ten bit pixel format", videoCodec: "h264", audioCodec: "aac", pixelFormat: "yuv420p10le", profile: "High 10", bitDepth: 10, want: false},
		{name: "four two two profile", videoCodec: "h264", audioCodec: "aac", pixelFormat: "yuv422p", profile: "High 4:2:2", bitDepth: 8, want: false},
		{name: "unsupported pixel format", videoCodec: "h264", audioCodec: "aac", pixelFormat: "yuv444p", profile: "High 4:4:4 Predictive", bitDepth: 8, want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := CanCopyMediaWithDetails(test.videoCodec, test.audioCodec, test.pixelFormat, test.profile, test.bitDepth); got != test.want {
				t.Fatalf("CanCopyMediaWithDetails(%q, %q, %q, %q, %d) = %v, want %v", test.videoCodec, test.audioCodec, test.pixelFormat, test.profile, test.bitDepth, got, test.want)
			}
		})
	}
}

func TestCanCopyWebMMediaRequiresBrowserNativeCodecs(t *testing.T) {
	tests := []struct {
		name       string
		videoCodec string
		audioCodec string
		want       bool
	}{
		{name: "vp9 opus", videoCodec: "vp9", audioCodec: "opus", want: true},
		{name: "vp8 no audio", videoCodec: "vp8", want: true},
		{name: "av1 vorbis", videoCodec: "av1", audioCodec: "vorbis", want: true},
		{name: "h264 opus", videoCodec: "h264", audioCodec: "opus", want: false},
		{name: "vp9 dts", videoCodec: "vp9", audioCodec: "dts", want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := CanCopyWebMMedia(test.videoCodec, test.audioCodec); got != test.want {
				t.Fatalf("CanCopyWebMMedia(%q, %q) = %v, want %v", test.videoCodec, test.audioCodec, got, test.want)
			}
		})
	}
}

func newFakeService(t *testing.T, workers int, maxBytes int64, delay time.Duration) *Service {
	t.Helper()
	directory := t.TempDir()
	script := filepath.Join(directory, "fake-ffmpeg.sh")
	contents := "#!/bin/sh\nfor last do :; done\noutdir=$(dirname \"$last\")\nprintf 'segment-data' > \"$outdir/segment-000000.ts.tmp\"\nmv \"$outdir/segment-000000.ts.tmp\" \"$outdir/segment-000000.ts\"\nprintf '#EXTM3U\\n#EXTINF:4,\\nsegment-000000.ts\\n#EXT-X-ENDLIST\\n' > \"$last.tmp\"\nmv \"$last.tmp\" \"$last\"\n"
	if delay > 0 {
		contents += "sleep " + strconv.FormatFloat(delay.Seconds(), 'f', 3, 64) + "\n"
	}
	if err := os.WriteFile(script, []byte(contents), 0o700); err != nil {
		t.Fatal(err)
	}
	service, err := New(Config{
		CacheDir: filepath.Join(directory, "cache"), MaxBytes: maxBytes,
		Workers: workers, FFmpegPath: script,
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}

func reserveJobs(t *testing.T, service *Service, count int) []Job {
	t.Helper()
	jobs := make([]Job, 0, count)
	for index := 0; index < count; index++ {
		input := Input{
			UserID: 1, Path: "/video-" + string(rune('a'+index)) + ".mkv",
			Identity: string(rune('1' + index)), SourcePath: "/source.mkv",
		}
		_, created, err := service.Reserve(input, func(job Job) (string, error) {
			jobs = append(jobs, job)
			return "task-" + job.ID[:6], nil
		})
		if err != nil || !created {
			t.Fatalf("reserve %d: created=%v err=%v", index, created, err)
		}
	}
	return jobs
}

func waitForState(t *testing.T, service *Service, job Job, state State) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		status, err := service.Get(job.ID, job.UserID)
		if err == nil && status.State == state {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	status, err := service.Get(job.ID, job.UserID)
	t.Fatalf("state = %#v, %v; want %s", status, err, state)
}
