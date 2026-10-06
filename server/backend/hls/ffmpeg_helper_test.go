package hls

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The Go test executable is an actual process on both Windows and Linux.
// This preserves worker, cancellation and artifact tests without shell fixtures.
func TestMain(tests *testing.M) {
	if os.Getenv("FILEWAY_TEST_FFMPEG_HELPER") != "1" {
		os.Exit(tests.Run())
	}
	if err := writeFakeFFmpegOutput(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	os.Exit(0)
}

func writeFakeFFmpegOutput() error {
	playlist := os.Args[len(os.Args)-1]
	segment := filepath.Join(filepath.Dir(playlist), "segment-000000.ts")
	if err := os.WriteFile(segment+".tmp", []byte("segment-data"), 0o600); err != nil {
		return err
	}
	if err := os.Rename(segment+".tmp", segment); err != nil {
		return err
	}
	if err := os.WriteFile(playlist+".tmp", []byte("#EXTM3U\n#EXTINF:4,\nsegment-000000.ts\n#EXT-X-ENDLIST\n"), 0o600); err != nil {
		return err
	}
	if err := os.Rename(playlist+".tmp", playlist); err != nil {
		return err
	}
	if delay, err := time.ParseDuration(os.Getenv("FILEWAY_TEST_FFMPEG_DELAY")); err == nil && delay > 0 {
		time.Sleep(delay)
	}
	return nil
}
