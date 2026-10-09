package hls

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestExportStreamsHLSBeforeMP4FinishesUsingOneEncode(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("FFmpeg unavailable")
	}
	root := t.TempDir()
	source := filepath.Join(root, "source.mkv")
	generate := exec.Command(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=24:duration=8", "-c:v", "libx264", "-g", "48", "-pix_fmt", "yuv420p", source)
	if output, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("fixture: %v %s", err, output)
	}
	helper, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	receipt, done := filepath.Join(root, "export-calls.jsonl"), filepath.Join(root, "export-done")
	t.Setenv("FILEWAY_TEST_PACED_EXPORT", ffmpeg)
	t.Setenv("FILEWAY_TEST_PACED_EXPORT_RECEIPT", receipt)
	t.Setenv("FILEWAY_TEST_PACED_EXPORT_DONE", done)
	service, err := New(Config{CacheDir: filepath.Join(root, "cache"), Workers: 1, FFmpegPath: helper})
	if err != nil {
		t.Fatal(err)
	}
	directoryName := "目录 [foo]' 空格"
	if runtime.GOOS != "windows" {
		directoryName = "目录:[foo]'|bar"
	}
	destination := filepath.Join(root, directoryName)
	if err := os.Mkdir(destination, 0o700); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(destination, ".transcode.mp4")
	live := false
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	err = service.Export(ctx, func() (Job, error) {
		return Job{UserID: 1, Path: "/source.mkv", SourcePath: source, Profile: ProfileForDimensions("source", 160, 90), DurationSeconds: 8}, nil
	}, output, "task", func(p Progress) error {
		if live || p.PlayableSeconds <= 0 || p.Phase == "finalizing" {
			return nil
		}
		playlist, err := os.ReadFile(filepath.Join(service.ExportDirectory("task"), "index.m3u8"))
		if err != nil {
			return err
		}
		if strings.Contains(string(playlist), "#EXT-X-ENDLIST") {
			return fmt.Errorf("HLS only became readable after export finished")
		}
		if _, err := os.Stat(done); !os.IsNotExist(err) {
			return fmt.Errorf("encoder already ended before HLS became playable: %v", err)
		}
		segment := ""
		for _, line := range strings.Split(string(playlist), "\n") {
			if line != "" && !strings.HasPrefix(line, "#") {
				segment = line
				break
			}
		}
		if segment == "" {
			return fmt.Errorf("live playlist has no segment")
		}
		if err := decodeExportFrame(ctx, ffmpeg, filepath.Join(service.ExportDirectory("task"), segment)); err != nil {
			return err
		}
		if _, err := os.Stat(done); !os.IsNotExist(err) {
			return fmt.Errorf("encoder ended before live HLS decoding finished: %v", err)
		}
		live = true
		t.Logf("decoded live HLS segment with %.3fs playable while the H264 export process was still running", p.PlayableSeconds)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !live {
		t.Fatal("no playable HLS segment before completion")
	}
	if info, err := os.Stat(output); err != nil || info.Size() == 0 {
		t.Fatalf("MP4 output: %v", err)
	}
	if duration := PlayableSeconds(service.ExportDirectory("task")); duration < 7.5 || duration > 8.5 {
		t.Fatalf("playlist duration=%v", duration)
	}
	for _, path := range []string{output, filepath.Join(service.ExportDirectory("task"), "index.m3u8")} {
		if err := decodeExportFrame(ctx, ffmpeg, path); err != nil {
			t.Fatal(err)
		}
	}
	calls, err := os.ReadFile(receipt)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(calls)), "\n")
	if len(lines) != 1 {
		t.Fatalf("export used %d FFmpeg processes, want one", len(lines))
	}
	var args []string
	if err := json.Unmarshal([]byte(lines[0]), &args); err != nil {
		t.Fatal(err)
	}
	encoders, tees := 0, 0
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-c:v" && args[i+1] == "libx264" {
			encoders++
		}
		if args[i] == "-f" && args[i+1] == "tee" {
			tees++
		}
	}
	if encoders != 1 || tees != 1 {
		t.Fatalf("not a single H264 tee encode: %q", args)
	}
	joined := strings.Join(exportTeeArgs(Job{SourcePath: source, Profile: DefaultMP4CopyProfile}, "out.mp4", "preview"), " ")
	if strings.Contains(joined, "libx264") || !strings.Contains(joined, "-f tee") {
		t.Fatal(joined)
	}
}

func decodeExportFrame(ctx context.Context, ffmpeg, path string) error {
	decode := exec.CommandContext(ctx, ffmpeg, "-hide_banner", "-loglevel", "error", "-i", path, "-map", "0:v:0", "-frames:v", "1", "-f", "framehash", "-")
	output, err := decode.CombinedOutput()
	if err != nil {
		return fmt.Errorf("decode %s: %w %s", filepath.Base(path), err, output)
	}
	for _, line := range strings.Split(string(output), "\n") {
		if strings.HasPrefix(line, "0,") {
			return nil
		}
	}
	return fmt.Errorf("decode %s produced no video frame: %s", filepath.Base(path), output)
}

func TestExportStillRejectsLiteralBackslashCachePathOnPOSIX(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("backslash is a native separator, not a literal filename character")
	}
	root := t.TempDir()
	service, err := New(Config{CacheDir: filepath.Join(root, "literal\\cache"), Workers: 1, FFmpegPath: "not-executed"})
	if err != nil {
		t.Fatal(err)
	}
	err = service.Export(context.Background(), func() (Job, error) { return Job{}, nil }, filepath.Join(root, "out.mp4"), "owned-backslash", func(Progress) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "不支持的封装字符") {
		t.Fatalf("literal backslash accepted: %v", err)
	}
}

func TestSourceQualityPreservesNonStandardDimensions(t *testing.T) {
	for _, dimensions := range [][2]int{{960, 540}, {3840, 1600}, {5312, 2988}} {
		profile := ProfileForDimensions("source", dimensions[0], dimensions[1])
		if profileMaxWidth(profile) != dimensions[0] || profileMaxHeight(profile) != dimensions[1] {
			t.Fatal(profile)
		}
	}
}
