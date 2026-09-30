package hls

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image/png"
	"os"
	"os/exec"
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
	args := ffmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8", 1280, 720)
	joined := strings.Join(args, "\x00")
	if !strings.Contains(joined, "pad=ceil(iw/2)*2:ceil(ih/2)*2") {
		t.Fatalf("ffmpeg filter does not pad odd dimensions: %q", joined)
	}
}

func TestFFmpegArgsExposeGrowingPlaylistAsSeekableEvent(t *testing.T) {
	args := ffmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8", 1280, 720)
	joined := strings.Join(args, "\x00")
	if !strings.Contains(joined, "-hls_playlist_type\x00event") {
		t.Fatalf("growing HLS playlist is not marked as an event: %q", joined)
	}
}

func TestQualityProfilesAreDistinctAndNeverUpscale(t *testing.T) {
	if got := ProfileForDimensions("4k", 3840, 1600); got != "h264-main-2160p-aac-hls4-v1" {
		t.Fatalf("cinema 4K profile = %q", got)
	}
	if got := ProfileForDimensions("2k", 3840, 1600); got != "h264-main-1440p-aac-hls4-v1" {
		t.Fatalf("cinema 2K profile = %q", got)
	}
	if got := ProfileForDimensions("4k", 1920, 800); got != "h264-main-1080p-aac-hls4-v1" {
		t.Fatalf("cinema 1080p no-upscale profile = %q", got)
	}
	if got := ProfileForQuality("1080p", 2160); got != "h264-main-1080p-aac-hls4-v1" {
		t.Fatalf("1080 profile = %q", got)
	}
	if got := ProfileForQuality("4k", 720); got != "h264-main-720p-aac-hls4-v1" {
		t.Fatalf("upscale profile = %q", got)
	}
	input := Input{UserID: 1, Path: "/movie.mkv", Identity: "v1", SourcePath: "/source.mkv"}
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	var first, second Job
	_, _, err := service.ReserveWithProfile(input, ProfileForQuality("1080p", 2160), func(job Job) (string, error) { first = job; return "one", nil })
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = service.ReserveWithProfile(input, ProfileForQuality("720p", 2160), func(job Job) (string, error) { second = job; return "two", nil })
	if err != nil {
		t.Fatal(err)
	}
	if first.ID == second.ID || first.Profile == second.Profile {
		t.Fatal("quality was not included in cache identity")
	}
}

func TestPGSSubtitleUsesDistinctCacheAndOverlay(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	first, second := 5, 6
	input := Input{UserID: 1, Path: "/movie.mkv", Identity: "v1", SourcePath: "/source.mkv"}
	start := func(job Job) (string, error) { return "task-" + job.ID, nil }
	plain, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	input.SubtitleStream = &first
	english, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	input.SubtitleStream = &second
	chinese, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	if plain.ID == english.ID || plain.ID == chinese.ID || english.ID == chinese.ID {
		t.Fatal("subtitle selection reused another artifact")
	}
	if english.ID == cacheKey(input.UserID, input.Path, input.Identity+"\x00subtitle=5", DefaultProfile) {
		t.Fatal("subtitle selection reused a cache artifact with the old EOF behavior")
	}
	if english.SubtitleStream == nil || *english.SubtitleStream != first {
		t.Fatalf("selected subtitle = %#v", english.SubtitleStream)
	}
	hlsArgs := strings.Join(ffmpegSubtitleArgs("/source.mkv", "/tmp/segment.ts", "/tmp/index.m3u8", 1920, 1080, first), "\x00")
	webmArgs := strings.Join(webMSubtitleArgs("/source.mkv", "/tmp/index.webm", 1920, 1080, second), "\x00")
	if !strings.Contains(hlsArgs, "[0:v:0][0:5]overlay=shortest=1") || !strings.Contains(hlsArgs, "-map\x00[v]") {
		t.Fatalf("HLS subtitle overlay missing: %q", hlsArgs)
	}
	if !strings.Contains(webmArgs, "[0:v:0][0:6]overlay=shortest=1") || !strings.Contains(webmArgs, "-map\x00[v]") {
		t.Fatalf("WebM subtitle overlay missing: %q", webmArgs)
	}
}

func TestAudioTrackSelectionUsesDistinctCacheAndFFmpegMap(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	audio := 4
	subtitle := 6
	input := Input{UserID: 1, Path: "/movie.mkv", Identity: "v1", SourcePath: "/source.mkv"}
	start := func(job Job) (string, error) { return "task-" + job.ID, nil }
	plain, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	input.AudioStream = &audio
	selected, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	input.SubtitleStream = &subtitle
	combined, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	if plain.ID == selected.ID || selected.ID == combined.ID || combined.ID == plain.ID {
		t.Fatal("audio or combined track selection reused another artifact")
	}
	if selected.AudioStream == nil || *selected.AudioStream != audio {
		t.Fatalf("selected audio = %#v", selected.AudioStream)
	}
	hlsArgs := strings.Join(ffmpegTrackArgs("/source.mkv", "/tmp/segment.ts", "/tmp/index.m3u8", 1920, 1080, &subtitle, &audio, false), "\x00")
	webmArgs := strings.Join(webMTrackArgs("/source.mkv", "/tmp/index.webm", 1920, 1080, &subtitle, &audio, false), "\x00")
	for _, args := range []string{hlsArgs, webmArgs} {
		if !strings.Contains(args, "-map\x000:4") || !strings.Contains(args, "[0:v:0][0:6]overlay") {
			t.Fatalf("selected tracks missing from FFmpeg args: %q", args)
		}
	}
}

func TestHDRToneMapUsesDistinctCacheAndSDRFilter(t *testing.T) {
	service := newFakeService(t, 1, DefaultMaxBytes, 0)
	input := Input{UserID: 1, Path: "/movie.mkv", Identity: "v1", SourcePath: "/source.mkv"}
	start := func(job Job) (string, error) { return "task-" + job.ID, nil }
	plain, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	input.HDR = true
	hdr, _, err := service.ReserveWithProfile(input, DefaultProfile, start)
	if err != nil {
		t.Fatal(err)
	}
	if plain.ID == hdr.ID || !hdr.HDR {
		t.Fatalf("HDR artifact reused SDR cache: plain=%#v hdr=%#v", plain, hdr)
	}
	args := strings.Join(ffmpegTrackArgs("/source.mkv", "/tmp/segment.ts", "/tmp/index.m3u8", 1920, 1080, nil, nil, true), "\x00")
	for _, want := range []string{"zscale=t=linear", "tonemap=tonemap=hable", "zscale=p=bt709:t=bt709:m=bt709", "-color_trc\x00bt709"} {
		if !strings.Contains(args, want) {
			t.Fatalf("HDR filter missing %q: %q", want, args)
		}
	}
	webmArgs := strings.Join(webMTrackArgs("/source.mkv", "/tmp/index.webm", 1920, 1080, nil, nil, true), "\x00")
	if !strings.Contains(webmArgs, "tonemap=tonemap=hable") || !strings.Contains(webmArgs, "-color_trc\x00bt709") {
		t.Fatalf("WebM HDR filter missing: %q", webmArgs)
	}
}

func TestWebMQualityChangesTheTranscodeAndCacheProfile(t *testing.T) {
	wide := WebMProfileForQuality("1080p", 2160)
	narrow := WebMProfileForQuality("480p", 2160)
	if wide == narrow || !IsWebMProfile(wide) || !IsWebMProfile(narrow) {
		t.Fatalf("WebM quality profiles = %q, %q", wide, narrow)
	}
	if IsWebMProfile(DefaultWebMCopyProfile) {
		t.Fatal("copy profile must not be treated as transcode")
	}
	if profileMaxWidth(wide) != 1920 || profileMaxHeight(wide) != 1080 ||
		profileMaxWidth(narrow) != 854 || profileMaxHeight(narrow) != 480 {
		t.Fatal("WebM quality did not change target dimensions")
	}
	args := strings.Join(webMArgs("/source.mkv", "/tmp/index.webm.tmp", profileMaxWidth(wide), profileMaxHeight(wide)), "\x00")
	if !strings.Contains(args, "min(1920,iw)") || !strings.Contains(args, "min(1080,ih)") {
		t.Fatalf("WebM FFmpeg scale not applied: %q", args)
	}
}

func TestFFmpegArgsUseSelectedMaximumDimensions(t *testing.T) {
	joined := strings.Join(ffmpegArgs("/source.mkv", "/tmp/segment-%06d.ts", "/tmp/index.m3u8", 1920, 1080), "\x00")
	if !strings.Contains(joined, "min(1920,iw)") || !strings.Contains(joined, "min(1080,ih)") {
		t.Fatalf("quality scale missing: %q", joined)
	}
}

func TestWebMArgsProduceBrowserSeekableCompatibilityFile(t *testing.T) {
	args := webMArgs("/source.mkv", "/tmp/index.webm.tmp", 1280, 720)
	joined := strings.Join(args, "\x00")
	for _, expected := range []string{"libvpx-vp9", "libopus", "-progress\x00pipe:1", "-threads\x002", "-f\x00webm", "/tmp/index.webm.tmp"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("WebM args missing %q: %q", expected, joined)
		}
	}
}

func TestBoundedVideoScaleFitsPortraitAndKeepsSmallLandscape(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("FFmpeg is unavailable")
	}
	for _, tc := range []struct {
		name                  string
		width, height         int
		wantWidth, wantHeight int
	}{
		{name: "portrait", width: 360, height: 640, wantWidth: 270, wantHeight: 480},
		{name: "small landscape", width: 640, height: 360, wantWidth: 640, wantHeight: 360},
	} {
		t.Run(tc.name, func(t *testing.T) {
			output := filepath.Join(t.TempDir(), "frame.png")
			args := []string{
				"-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
				"-i", fmt.Sprintf("color=c=red:s=%dx%d:d=0.1", tc.width, tc.height),
				"-vf", boundedVideoScale(854, 480), "-frames:v", "1", "-f", "image2", output,
			}
			if outputText, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
				t.Fatalf("FFmpeg scale failed: %v: %s", err, outputText)
			}
			file, err := os.Open(output)
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			config, err := png.DecodeConfig(file)
			if err != nil {
				t.Fatal(err)
			}
			if config.Width != tc.wantWidth || config.Height != tc.wantHeight {
				t.Fatalf("scaled dimensions = %dx%d, want %dx%d", config.Width, config.Height, tc.wantWidth, tc.wantHeight)
			}
		})
	}
}

func TestTranscodeCacheVersionLeavesCopyCacheUntouched(t *testing.T) {
	legacy := func(profile string) string {
		digest := sha256.Sum256([]byte("1\x00/movie.mkv\x00v1\x00" + profile))
		return hex.EncodeToString(digest[:])
	}
	if got := cacheKey(1, "/movie.mkv", "v1", DefaultWebMProfile); got == legacy(DefaultWebMProfile) {
		t.Fatal("WebM transcode still shares the old width-only cache")
	}
	if got := cacheKey(1, "/movie.mkv", "v1", DefaultProfile); got == legacy(DefaultProfile) {
		t.Fatal("HLS transcode still shares the old width-only cache")
	}
	for _, profile := range []string{DefaultCopyProfile, DefaultMP4CopyProfile, DefaultWebMCopyProfile} {
		if got := cacheKey(1, "/movie.mkv", "v1", profile); got != legacy(profile) {
			t.Fatalf("copy cache changed for %q", profile)
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
