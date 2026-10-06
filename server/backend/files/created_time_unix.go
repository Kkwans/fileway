//go:build linux

package files

import (
	"time"

	"golang.org/x/sys/unix"
)

// setCreatedTime uses statx birth time only for an OS-backed filesystem. Afero
// memory/test filesystems intentionally return no value so the API can omit
// the row rather than mislabeling ctime.
func setCreatedTime(file *FileInfo) {
	if file == nil || file.Fs == nil {
		return
	}
	fullPath, ok := osBackedPath(file.Fs, file.Path)
	if !ok {
		return
	}
	var stat unix.Statx_t
	if err := unix.Statx(unix.AT_FDCWD, fullPath, unix.AT_SYMLINK_NOFOLLOW, unix.STATX_BTIME, &stat); err != nil {
		return
	}
	if stat.Mask&unix.STATX_BTIME == 0 {
		return
	}
	created := time.Unix(int64(stat.Btime.Sec), int64(stat.Btime.Nsec))
	file.Created = &created
}
