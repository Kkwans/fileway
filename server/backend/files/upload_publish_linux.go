//go:build linux

package files

import (
	"errors"
	"os"
	"path/filepath"

	"golang.org/x/sys/unix"
)

func publishUploadNative(from, to string, replace bool) error {
	if replace {
		if err := os.Rename(from, to); err != nil {
			return err
		}
		return syncUploadDirectory(to)
	}
	err := unix.Renameat2(unix.AT_FDCWD, from, unix.AT_FDCWD, to, unix.RENAME_NOREPLACE)
	if errors.Is(err, unix.ENOSYS) || errors.Is(err, unix.EINVAL) {
		if err := os.Link(from, to); err != nil {
			return err
		}
		if err := os.Remove(from); err != nil {
			return err
		}
		return syncUploadDirectory(to)
	}
	if err != nil {
		return err
	}
	return syncUploadDirectory(to)
}

func syncUploadDirectory(target string) error {
	directory, err := os.Open(filepath.Dir(target))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
