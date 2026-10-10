package fileutils

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"strings"

	"github.com/spf13/afero"
)

// CopyDir copies a directory from source to dest and all
// of its sub-directories. It doesn't stop if it finds an error
// during the copy. Returns an error if any.
func CopyDir(afs afero.Fs, source, dest string, fileMode, dirMode fs.FileMode) error {
	return copyDirPublished(afs, source, dest, fileMode, dirMode, true)
}

func copyDirPublished(afs afero.Fs, source, dest string, fileMode, dirMode fs.FileMode, overwrite bool) error {
	source, dest = path.Clean(source), path.Clean(dest)
	if source == dest || strings.HasPrefix(dest, strings.TrimSuffix(source, "/")+"/") {
		return os.ErrInvalid
	}
	// Get properties of source.
	srcinfo, err := lstat(afs, source)
	if err != nil {
		return err
	}
	if !srcinfo.IsDir() || srcinfo.Mode()&os.ModeSymlink != 0 {
		return os.ErrInvalid
	}
	// A merge must not follow a target directory alias back into the source or
	// outside the checked tree. Ordinary directory merging remains unchanged.
	if target, statErr := lstat(afs, dest); statErr == nil {
		if target.Mode()&os.ModeSymlink != 0 {
			return os.ErrInvalid
		}
	} else if !os.IsNotExist(statErr) {
		return statErr
	}

	if target, statErr := afs.Stat(dest); statErr == nil {
		if os.SameFile(srcinfo, target) {
			return os.ErrInvalid
		}
		if !overwrite {
			return os.ErrExist
		}
	} else if !os.IsNotExist(statErr) {
		return statErr
	}

	// Mkdir is atomic for a no-overwrite root; an intervening creator wins.
	if err = afs.MkdirAll(path.Dir(dest), dirMode); err != nil {
		return err
	}
	err = afs.Mkdir(dest, srcinfo.Mode())
	if os.IsExist(err) && overwrite {
		target, statErr := lstat(afs, dest)
		if statErr != nil {
			return statErr
		}
		if target.Mode()&os.ModeSymlink != 0 || os.SameFile(srcinfo, target) {
			return os.ErrInvalid
		}
		if !target.IsDir() {
			return os.ErrExist
		}
		err = nil
	}
	if err != nil {
		return err
	}

	dir, err := afs.Open(source)
	if err != nil {
		return err
	}
	// The directory is read-only; preserve traversal/copy errors below.
	defer func() { _ = dir.Close() }()

	var errs []error
	for {
		obs, readErr := dir.Readdir(64)
		for _, obj := range obs {
			fsource := source + "/" + obj.Name()
			fdest := dest + "/" + obj.Name()

			if obj.IsDir() {
				// Create sub-directories, recursively.
				err = copyDirPublished(afs, fsource, fdest, fileMode, dirMode, overwrite)
			} else {
				// Perform the file copy.
				err = copyFilePublished(afs, fsource, fdest, fileMode, dirMode, overwrite)
			}
			if err != nil {
				errs = append(errs, err)
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			errs = append(errs, readErr)
			break
		}
	}

	if len(errs) > 0 {
		return errors.Join(errs...)
	}

	return nil
}
