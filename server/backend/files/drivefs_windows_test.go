//go:build windows

package files

import (
	"os"
	"path/filepath"
	"testing"
)

func TestScopedDriveFsCreatesMissingScopeRootBeforeFirstUpload(t *testing.T) {
	root := filepath.Join(t.TempDir(), "new-account")
	filesystem := NewScopedDriveFs(WindowsPathToVirtual(root))
	if err := filesystem.MkdirAll("/", 0700); err != nil {
		t.Fatal(err)
	}
	part, err := filesystem.OpenFile("/owned.part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal("first upload could not create its scoped part", err)
	}
	if err := part.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "owned.part")); err != nil {
		t.Fatal(err)
	}
}
