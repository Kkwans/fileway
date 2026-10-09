package files

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/spf13/afero"
)

type checksumHookFS struct {
	afero.Fs
	onRead func()
}

type checksumHookFile struct {
	afero.File
	onRead func()
}

func (fs checksumHookFS) Open(name string) (afero.File, error) {
	file, err := fs.Fs.Open(name)
	if err != nil {
		return nil, err
	}
	return &checksumHookFile{File: file, onRead: fs.onRead}, nil
}

func (file *checksumHookFile) Read(buffer []byte) (int, error) {
	n, err := file.File.Read(buffer)
	if file.onRead != nil {
		hook := file.onRead
		file.onRead = nil
		hook()
	}
	return n, err
}

func TestChecksumContextRejectsCancellationAndChangedFile(t *testing.T) {
	for _, cancelRead := range []bool{false, true} {
		fs := afero.NewMemMapFs()
		if err := afero.WriteFile(fs, "/owned.bin", []byte("owned-checksum"), 0o600); err != nil {
			t.Fatal(err)
		}
		stat, err := fs.Stat("/owned.bin")
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		file := &FileInfo{Fs: checksumHookFS{Fs: fs, onRead: func() {
			if cancelRead {
				cancel()
			} else {
				if err := fs.Chtimes("/owned.bin", time.Now(), stat.ModTime().Add(time.Second)); err != nil {
					t.Error(err)
				}
			}
		}}, Path: "/owned.bin", Size: stat.Size(), ModTime: stat.ModTime()}
		err = file.ChecksumContext(ctx, "sha256")
		cancel()
		want := ErrChecksumSourceChanged
		if cancelRead {
			want = context.Canceled
		}
		if !errors.Is(err, want) || len(file.Checksums) != 0 {
			t.Fatalf("cancel=%t digest published for interrupted source: %v %#v", cancelRead, err, file.Checksums)
		}
	}
}
