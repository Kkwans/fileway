//go:build windows

package files

import (
	"io"
	"path/filepath"
	"strings"
	"testing"
)

func TestScopedDriveFilesystemDoesNotExposeOtherDrives(t *testing.T) {
	root := t.TempDir()
	filesystem := NewScopedDriveFs(WindowsPathToVirtual(root))
	for _, name := range []string{"/", "/photo.jpg", "/../../C/Windows", "/D/other"} {
		resolved, err := filesystem.RealPath(name)
		if err != nil {
			t.Fatal(err)
		}
		relative, err := filepath.Rel(root, resolved)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			t.Fatalf("request %q escaped user scope: %q (%v)", name, resolved, err)
		}
	}
	file, err := filesystem.Open("/")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = file.Close() }()
	if info, err := file.Stat(); err != nil || !info.IsDir() {
		t.Fatalf("scoped root: %v %v", info, err)
	}
}

func TestVirtualDriveRootUsesDirectoryEOF(t *testing.T) {
	directory := &driveRootDir{}
	if _, err := directory.Readdir(1); err != io.EOF {
		t.Fatalf("empty root iteration = %v", err)
	}
}
