//go:build windows

package files

import (
	"errors"
	"os"
	"syscall"

	"golang.org/x/sys/windows"
)

func publishUploadNative(from, to string, replace bool) error {
	source, err := windows.UTF16PtrFromString(from)
	if err != nil {
		return err
	}
	target, err := windows.UTF16PtrFromString(to)
	if err != nil {
		return err
	}
	flags := uint32(windows.MOVEFILE_WRITE_THROUGH)
	if replace {
		flags |= windows.MOVEFILE_REPLACE_EXISTING
	}
	err = windows.MoveFileEx(source, target, flags)
	// Go's Windows Errno.Is does not map ERROR_NOT_SAME_DEVICE to EXDEV.
	// Keep cross-volume moves on the caller's cancellable copy/progress path;
	// MOVEFILE_COPY_ALLOWED would perform an unobserved copy inside Win32.
	if errors.Is(err, windows.ERROR_NOT_SAME_DEVICE) {
		return syscall.EXDEV
	}
	if errors.Is(err, windows.ERROR_ALREADY_EXISTS) || errors.Is(err, windows.ERROR_FILE_EXISTS) {
		return os.ErrExist
	}
	return err
}
