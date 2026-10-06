package mediatools

import (
	"os"
	"path/filepath"
	"testing"
)

func TestExplicitMediaToolOverride(t *testing.T) {
	tool := filepath.Join(t.TempDir(), "ffmpeg.exe")
	if err := os.WriteFile(tool, []byte("test fixture"), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("FFMPEG_PATH", tool)
	resolved, err := Lookup("ffmpeg")
	if err != nil || resolved != tool {
		t.Fatalf("configured media tool = %q, %v", resolved, err)
	}
}

func TestInvalidOverrideDoesNotSilentlySelectAnotherTool(t *testing.T) {
	t.Setenv("FFMPEG_PATH", filepath.Join(t.TempDir(), "missing.exe"))
	if _, err := Lookup("ffmpeg"); err == nil {
		t.Fatal("missing configured tool unexpectedly accepted")
	}
	if _, err := Lookup("../filebrowser"); err == nil {
		t.Fatal("unknown tool accepted")
	}
}
