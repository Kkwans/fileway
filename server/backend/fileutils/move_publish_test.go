package fileutils

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
)

// Reuse the owned native copy fixture/faults. Only the original rename is
// EXDEV; destination-local publication still performs an actual OS rename.
type moveFaultFS struct {
	afero.Fs
	source, target string
	renameErr      error
	openFailure    bool
	publishFailure bool
	postPublishErr bool
	removeFailure  bool
	sourceOpens    int
	targetRemoves  int
}

func (f *moveFaultFS) LstatIfPossible(name string) (os.FileInfo, bool, error) {
	return f.Fs.(afero.Lstater).LstatIfPossible(name)
}
func (f *moveFaultFS) Open(name string) (afero.File, error) {
	if filepath.Clean(name) == f.source {
		f.sourceOpens++
		if f.openFailure {
			return nil, errCopyInjected
		}
	}
	return f.Fs.Open(name)
}
func (f *moveFaultFS) Rename(from, to string) error {
	if filepath.Clean(from) == f.source && filepath.Clean(to) == f.target {
		if f.renameErr != nil {
			return f.renameErr
		}
	}
	if files.IsUploadPartPath(from) && filepath.Clean(to) == f.target {
		if f.publishFailure {
			return errCopyInjected
		}
		if err := f.Fs.Rename(from, to); err != nil {
			return err
		}
		if f.postPublishErr {
			return errCopyInjected // Publication succeeded, directory sync did not.
		}
		return nil
	}
	return f.Fs.Rename(from, to)
}
func (f *moveFaultFS) Remove(name string) error {
	if filepath.Clean(name) == f.target {
		f.targetRemoves++
	}
	if filepath.Clean(name) == f.source && f.removeFailure {
		return errCopyInjected
	}
	return f.Fs.Remove(name)
}
func (f *moveFaultFS) RemoveAll(name string) error {
	if filepath.Clean(name) == f.source && f.removeFailure {
		return errCopyInjected
	}
	return f.Fs.RemoveAll(name)
}
func moveNativeFixture(t *testing.T) (*moveFaultFS, *copyFaultFS) {
	t.Helper()
	base, fault, _ := copyNativeFixture(t)
	return &moveFaultFS{Fs: base, source: filepath.Clean("/source.txt"), target: filepath.Clean("/target.txt"), renameErr: syscall.EXDEV}, fault
}

func TestMoveCrossDevicePreparationFailurePreservesBothFiles(t *testing.T) {
	for _, kind := range []string{"open", "read", "write", "chmod", "sync", "source-close", "destination-close", "publish"} {
		t.Run(kind, func(t *testing.T) {
			fs, fault := moveNativeFixture(t)
			fs.openFailure, fs.publishFailure = kind == "open", kind == "publish"
			if !fs.openFailure && !fs.publishFailure {
				fault.fault = kind
			}
			err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750)
			fault.fault, fs.openFailure = "", false
			if !errors.Is(err, errCopyInjected) {
				t.Errorf("move error=%v, want injected %s", err, kind)
			}
			if fs.targetRemoves != 0 {
				t.Errorf("failed move attempted to unlink existing target %d times", fs.targetRemoves)
			}
			assertCopyBytes(t, fs, "/source.txt", "new-source-bytes")
			assertCopyBytes(t, fs, "/target.txt", "old-target-bytes")
			assertNoCopyPart(t, fs)
		})
	}
}

func TestMoveCrossDevicePostPublicationErrorKeepsBothFiles(t *testing.T) {
	fs, _ := moveNativeFixture(t)
	fs.postPublishErr = true
	if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); !errors.Is(err, errCopyInjected) {
		t.Errorf("publication error=%v", err)
	}
	if fs.targetRemoves != 0 {
		t.Fatal("post-publication error unlinked destination")
	}
	// A known publication is retained; never pretend the old destination can
	// be recreated by deleting the new complete file on a later sync error.
	assertCopyBytes(t, fs, "/source.txt", "new-source-bytes")
	assertCopyBytes(t, fs, "/target.txt", "new-source-bytes")
	assertNoCopyPart(t, fs)
}

func TestMoveCrossDeviceSourceRemovalFailureLeavesTwoCompleteFiles(t *testing.T) {
	fs, _ := moveNativeFixture(t)
	fs.removeFailure = true
	if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); !errors.Is(err, errCopyInjected) {
		t.Fatalf("source cleanup error=%v", err)
	}
	assertCopyBytes(t, fs, "/source.txt", "new-source-bytes")
	assertCopyBytes(t, fs, "/target.txt", "new-source-bytes")
	assertNoCopyPart(t, fs)
}

func TestMoveCrossDeviceSuccessPublishesThenRemovesSource(t *testing.T) {
	fs, _ := moveNativeFixture(t)
	if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); err != nil {
		t.Fatal(err)
	}
	if _, err := fs.Stat("/source.txt"); !os.IsNotExist(err) {
		t.Fatalf("source remains: %v", err)
	}
	assertCopyBytes(t, fs, "/target.txt", "new-source-bytes")
	assertNoCopyPart(t, fs)
}

func TestMoveNonCrossDeviceErrorNeverCopiesOrDeletes(t *testing.T) {
	for _, failure := range []error{os.ErrPermission, os.ErrExist, os.ErrNotExist} {
		fs, _ := moveNativeFixture(t)
		fs.renameErr = failure
		if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); !errors.Is(err, failure) {
			t.Fatalf("rename error=%v, want %v", err, failure)
		}
		if fs.sourceOpens != 0 || fs.targetRemoves != 0 {
			t.Fatal("non-EXDEV error entered copy/cleanup fallback")
		}
		assertCopyBytes(t, fs, "/source.txt", "new-source-bytes")
		assertCopyBytes(t, fs, "/target.txt", "old-target-bytes")
	}
}

func TestMoveSameFilesystemKeepsRenameContract(t *testing.T) {
	fs, fault := moveNativeFixture(t)
	fs.renameErr, fault.fault = nil, "read"
	if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); err != nil {
		t.Fatal(err)
	}
	fault.fault = ""
	if fs.sourceOpens != 0 {
		t.Fatal("ordinary rename unexpectedly copied bytes")
	}
	assertCopyBytes(t, fs, "/target.txt", "new-source-bytes")
}

func TestMoveCrossDeviceDirectoryRetainsLegacyMerge(t *testing.T) {
	fs, _ := moveNativeFixture(t)
	for _, name := range []string{"/source.txt", "/target.txt"} {
		if err := fs.Fs.Remove(name); err != nil {
			t.Fatal(err)
		}
		if err := fs.Mkdir(name, 0o750); err != nil {
			t.Fatal(err)
		}
	}
	for name, body := range map[string]string{"/source.txt/child": "new-child", "/target.txt/child": "old-child", "/target.txt/unrelated": "keep"} {
		if err := afero.WriteFile(fs, name, []byte(body), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	if err := MoveFile(fs, "/source.txt", "/target.txt", 0o640, 0o750); err != nil {
		t.Fatal(err)
	}
	assertCopyBytes(t, fs, "/target.txt/child", "new-child")
	assertCopyBytes(t, fs, "/target.txt/unrelated", "keep")
	if _, err := fs.Stat("/source.txt"); !os.IsNotExist(err) {
		t.Fatalf("source tree remains: %v", err)
	}
}
