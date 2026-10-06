//go:build windows

package files

import (
	"github.com/spf13/afero"
	"golang.org/x/sys/windows"
)

func fileIdentity(filesystem afero.Fs, name string) *Identity {
	native, ok := osBackedPath(filesystem, name)
	if !ok {
		return nil
	}
	path, err := windows.UTF16PtrFromString(native)
	if err != nil {
		return nil
	}
	handle, err := windows.CreateFile(path, 0, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT, 0)
	if err != nil {
		return nil
	}
	defer func() { _ = windows.CloseHandle(handle) }()
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil || info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return nil
	}
	mode := uint32(0o100000)
	if info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY != 0 {
		mode = 0o040000
	}
	return &Identity{DeviceMajor: info.VolumeSerialNumber,
		Inode: uint64(info.FileIndexHigh)<<32 | uint64(info.FileIndexLow), Links: uint64(info.NumberOfLinks), Mode: mode}
}

func identityModeIsRegular(mode uint32) bool { return mode&0o170000 == 0o100000 }
