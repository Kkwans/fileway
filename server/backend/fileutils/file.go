package fileutils

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"path"
	"syscall"

	"github.com/spf13/afero"
)

// MoveFile moves file from src to dst.
// By default the rename filesystem system call is used. If src and dst point to different volumes
// the file copy is used as a fallback
func MoveFile(afs afero.Fs, src, dst string, fileMode, dirMode fs.FileMode) error {
	if err := afs.Rename(src, dst); err == nil {
		return nil
	} else if !errors.Is(err, syscall.EXDEV) {
		return err
	}
	// A cross-device rename is the only safe fallback case. Permission,
	// conflict and source errors must reach the caller unchanged.
	info, err := lstat(afs, src)
	if err != nil {
		return err
	}
	if info.Mode()&fs.ModeSymlink != 0 {
		return fmt.Errorf("不支持复制符号链接: %s", src)
	}
	if info.Mode().IsRegular() {
		if err := copyFilePublished(afs, src, dst, fileMode, dirMode, true); err != nil {
			// A publication/sync error can leave a complete destination. It is
			// never safe to remove that path or the source on a failed copy.
			return err
		}
		return afs.Remove(src)
	}
	// Preserve legacy directory merging, fail-fast traversal and link rejection.
	// This is not the task runner's whole-tree replacement transaction.
	if err := CopyContext(context.Background(), afs, src, dst, fileMode, dirMode); err != nil {
		return err
	}
	return afs.RemoveAll(src)
}

// CopyFile copies a file from source to dest and returns
// an error if any.
func CopyFile(afs afero.Fs, source, dest string, fileMode, dirMode fs.FileMode) error {
	return copyFilePublished(afs, source, dest, fileMode, dirMode, true)
}

// CommonPrefix returns common directory path of provided files
func CommonPrefix(sep byte, paths ...string) string {
	// Handle special cases.
	switch len(paths) {
	case 0:
		return ""
	case 1:
		return path.Clean(paths[0])
	}

	// Note, we treat string as []byte, not []rune as is often
	// done in Go. (And sep as byte, not rune). This is because
	// most/all supported OS' treat paths as string of non-zero
	// bytes. A filename may be displayed as a sequence of Unicode
	// runes (typically encoded as UTF-8) but paths are
	// not required to be valid UTF-8 or in any normalized form
	// (e.g. "é" (U+00C9) and "é" (U+0065,U+0301) are different
	// file names.
	c := []byte(path.Clean(paths[0]))

	// We add a trailing sep to handle the case where the
	// common prefix directory is included in the path list
	// (e.g. /home/user1, /home/user1/foo, /home/user1/bar).
	// path.Clean will have cleaned off trailing / separators with
	// the exception of the root directory, "/" (in which case we
	// make it "//", but this will get fixed up to "/" below).
	c = append(c, sep)

	// Ignore the first path since it's already in c
	for _, v := range paths[1:] {
		// Clean up each path before testing it
		v = path.Clean(v) + string(sep)

		// Find the first non-common byte and truncate c
		if len(v) < len(c) {
			c = c[:len(v)]
		}
		for i := 0; i < len(c); i++ {
			if v[i] != c[i] {
				c = c[:i]
				break
			}
		}
	}

	// Remove trailing non-separator characters and the final separator
	for i := len(c) - 1; i >= 0; i-- {
		if c[i] == sep {
			c = c[:i]
			break
		}
	}

	return string(c)
}
