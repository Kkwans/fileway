package files

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/spf13/afero"
)

func TestCreatedTimeNeverBorrowsHostTimeForMemoryFilesystem(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "file"), []byte("host"), 0600); err != nil {
		t.Fatal(err)
	}
	virtual := afero.NewMemMapFs()
	if err := virtual.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	scoped := afero.NewBasePathFs(virtual, root)
	if err := afero.WriteFile(scoped, "/file", []byte("virtual"), 0600); err != nil {
		t.Fatal(err)
	}
	if CreatedTime(scoped, "/file") != nil {
		t.Fatal("memory file acquired native file birth time")
	}
}

func TestCreatedTimeDoesNotUseModifiedTime(t *testing.T) {
	if runtime.GOOS != "linux" && runtime.GOOS != "windows" {
		t.Skip("birth time unsupported on this platform")
	}
	fs := afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
	if err := afero.WriteFile(fs, "/file", []byte("file"), 0600); err != nil {
		t.Fatal(err)
	}
	first := CreatedTime(fs, "/file")
	if first == nil {
		t.Skip("filesystem does not expose birth time")
	}
	old := time.Unix(100, 0)
	if err := fs.Chtimes("/file", old, old); err != nil {
		t.Fatal(err)
	}
	after := CreatedTime(fs, "/file")
	if after == nil || !first.Equal(*after) || after.Equal(old) {
		t.Fatalf("birth time replaced by mtime: %v %v", first, after)
	}
}
