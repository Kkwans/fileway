//go:build windows

package files

import (
	"os"

	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

// Allow a concurrent restore/delete while enumeration owns a directory handle.
// Callers still verify identity, links and cancellation before processing entries.
func OpenDirectoryForScan(filesystem afero.Fs, name string) (afero.File, error) {
	native, ok := osBackedPath(filesystem, name)
	if !ok {
		return filesystem.Open(name)
	}
	path, err := windows.UTF16PtrFromString(native)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(path, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(handle), native), nil
}
