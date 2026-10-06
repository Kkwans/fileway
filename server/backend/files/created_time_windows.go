//go:build windows

package files

import (
	"os"
	"syscall"
	"time"
)

func setCreatedTime(file *FileInfo) {
	if file == nil || file.Fs == nil {
		return
	}
	path, ok := osBackedPath(file.Fs, file.Path)
	if !ok {
		return
	}
	info, err := os.Lstat(path)
	if err != nil {
		return
	}
	data, ok := info.Sys().(*syscall.Win32FileAttributeData)
	if !ok || data == nil {
		return
	}
	created := time.Unix(0, data.CreationTime.Nanoseconds())
	file.Created = &created
}
