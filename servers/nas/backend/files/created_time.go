package files

import (
	"os"
	"path/filepath"
	"time"

	"github.com/spf13/afero"
)

// CreatedTime reuses the platform birth-time implementation without reading
// file content, enumerating directories or substituting modification/ctime.
func CreatedTime(filesystem afero.Fs, path string) *time.Time {
	file := &FileInfo{Fs: filesystem, Path: path}
	setCreatedTime(file)
	return file.Created
}

// BasePathFs can also wrap a memory filesystem. Prove that the scoped entry
// and native entry describe the same inode before reading OS-only metadata.
func osBackedPath(filesystem afero.Fs, name string) (string, bool) {
	var native string
	switch fs := filesystem.(type) {
	case *afero.BasePathFs:
		native = filepath.Clean(afero.FullBaseFsPath(fs, name))
	case *afero.OsFs:
		native = filepath.Clean(name)
	default:
		return "", false
	}
	lstater, ok := filesystem.(afero.Lstater)
	if !ok {
		return "", false
	}
	scoped, supported, err := lstater.LstatIfPossible(name)
	if err != nil || !supported || scoped.Sys() == nil {
		return "", false
	}
	actual, err := os.Lstat(native)
	if err != nil || !os.SameFile(scoped, actual) {
		return "", false
	}
	return native, true
}
