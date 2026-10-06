package hls

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
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
	generate := exec.Command(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=4:duration=8", "-c:v", "libx264", "-g", "16", "-pix_fmt", "yuv420p", source)
	if output, err := generate.CombinedOutput(); err != nil {
		t.Fatalf("fixture: %v %s", err, output)
	}
	script := filepath.Join(root, "paced-ffmpeg.sh")
	if err := os.WriteFile(script, []byte(fmt.Sprintf("#!/bin/sh\nexec '%s' -re \"$@\"\n", ffmpeg)), 0o700); err != nil {
		t.Fatal(err)
	}
	service, err := New(Config{CacheDir: filepath.Join(root, "cache"), Workers: 1, FFmpegPath: script})
	if err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "目录:[foo]'|bar")
	if err := os.Mkdir(destination, 0o700); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(destination, ".transcode.mp4")
	live := false
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err = service.Export(ctx, func() (Job, error) {
		return Job{UserID: 1, Path: "/source.mkv", SourcePath: source, Profile: DefaultMP4CopyProfile, DurationSeconds: 8}, nil
	}, output, "task", func(p Progress) error {
		if p.PlayableSeconds > 0 && p.Phase != "finalizing" {
			live = true
		}
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
	decode := exec.Command(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", filepath.Join(service.ExportDirectory("task"), "index.m3u8"), "-frames:v", "1", "-f", "null", "-")
	if out, err := decode.CombinedOutput(); err != nil {
		t.Fatalf("HLS decoding: %v %s", err, out)
	}
	joined := strings.Join(exportTeeArgs(Job{SourcePath: source, Profile: DefaultMP4CopyProfile}, "out.mp4", "preview"), " ")
	if strings.Contains(joined, "libx264") || !strings.Contains(joined, "-f tee") {
		t.Fatal(joined)
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
