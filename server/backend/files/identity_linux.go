//go:build linux

package files

import (
	"github.com/spf13/afero"
	"golang.org/x/sys/unix"
)

func fileIdentity(filesystem afero.Fs, path string) *Identity {
	native, ok := osBackedPath(filesystem, path)
	if !ok {
		return nil
	}
	var stat unix.Statx_t
	const required = unix.STATX_INO | unix.STATX_NLINK | unix.STATX_TYPE | unix.STATX_UID | unix.STATX_GID
	if err := unix.Statx(unix.AT_FDCWD, native, unix.AT_SYMLINK_NOFOLLOW, required, &stat); err != nil {
		return nil
	}
	if stat.Mask&required != required {
		return nil
	}
	return &Identity{DeviceMajor: stat.Dev_major, DeviceMinor: stat.Dev_minor, Inode: stat.Ino, Links: uint64(stat.Nlink), Mode: uint32(stat.Mode), UID: stat.Uid, GID: stat.Gid}
}

func identityModeIsRegular(mode uint32) bool {
	return mode&unix.S_IFMT == unix.S_IFREG
}
