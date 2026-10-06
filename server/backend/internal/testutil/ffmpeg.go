// Package testutil holds portable process fixtures shared by server tests.
package testutil

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func FFmpegExecutable(t *testing.T, delay time.Duration) string {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("FILEWAY_TEST_FFMPEG_HELPER", "1")
	t.Setenv("FILEWAY_TEST_FFMPEG_DELAY", delay.String())
	return executable
}

// RunFFmpegHelper is called by each package's test entry point, before flags.
func RunFFmpegHelper() {
	if os.Getenv("FILEWAY_TEST_FFMPEG_HELPER") != "1" {
		return
	}
	if err := writeOutput(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	os.Exit(0)
}

func writeOutput() error {
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
