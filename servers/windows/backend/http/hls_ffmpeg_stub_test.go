package fbhttp

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/hls"
)

// writeFakeFFmpeg builds a tiny Go stub transcoder (works on Windows and Unix).
// Unix .sh stubs cannot be exec'd on Windows.
func writeFakeFFmpeg(t *testing.T, directory string, slow bool) string {
	t.Helper()
	src := filepath.Join(directory, "fake_ffmpeg_main.go")
	code := `package main
import (
  "os"
  "path/filepath"
  "time"
)
func main() {
  if len(os.Args) < 2 { os.Exit(2) }
  last := os.Args[len(os.Args)-1]
  dir := filepath.Dir(last)
  _ = os.WriteFile(filepath.Join(dir, "segment-000000.ts"), []byte("segment-data"), 0o644)
  playlist := "#EXTM3U\n#EXTINF:4,\nsegment-000000.ts\n#EXT-X-ENDLIST\n"
  _ = os.WriteFile(last, []byte(playlist), 0o644)
  if os.Getenv("WINFB_FAKE_FFMPEG_SLOW") == "1" {
    time.Sleep(5 * time.Second)
  }
}
`
	if err := os.WriteFile(src, []byte(code), 0o600); err != nil {
		t.Fatal(err)
	}
	exe := filepath.Join(directory, "fake-ffmpeg")
	if runtime.GOOS == "windows" {
		exe += ".exe"
	}
	build := exec.Command("go", "build", "-o", exe, src)
	build.Env = os.Environ()
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build fake ffmpeg: %v\n%s", err, out)
	}
	if slow {
		t.Setenv("WINFB_FAKE_FFMPEG_SLOW", "1")
	} else {
		t.Setenv("WINFB_FAKE_FFMPEG_SLOW", "0")
	}
	return exe
}

func newHTTPHLSService(t *testing.T, slow bool) *hls.Service {
	t.Helper()
	directory := t.TempDir()
	script := writeFakeFFmpeg(t, directory, slow)
	service, err := hls.New(hls.Config{
		CacheDir: filepath.Join(directory, "cache"), MaxBytes: hls.DefaultMaxBytes,
		Workers: 1, FFmpegPath: script,
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}
