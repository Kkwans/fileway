package fileutils

import (
	"errors"
	"io"
	"io/fs"

	"github.com/spf13/afero"
)

// CopyDir copies a directory from source to dest and all
// of its sub-directories. It doesn't stop if it finds an error
// during the copy. Returns an error if any.
func CopyDir(afs afero.Fs, source, dest string, fileMode, dirMode fs.FileMode) error {
	// Get properties of source.
	srcinfo, err := afs.Stat(source)
	if err != nil {
		return err
	}

	// Create the destination directory.
	err = afs.MkdirAll(dest, srcinfo.Mode())
	if err != nil {
		return err
	}

	dir, err := afs.Open(source)
	if err != nil {
		return err
	}
	defer dir.Close()

	var errs []error
	for {
		obs, readErr := dir.Readdir(64)
		for _, obj := range obs {
			fsource := source + "/" + obj.Name()
			fdest := dest + "/" + obj.Name()

			if obj.IsDir() {
				// Create sub-directories, recursively.
				err = CopyDir(afs, fsource, fdest, fileMode, dirMode)
			} else {
				// Perform the file copy.
				err = CopyFile(afs, fsource, fdest, fileMode, dirMode)
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
