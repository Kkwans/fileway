//go:build !windows

package files

import "github.com/spf13/afero"

func OpenDirectoryForScan(filesystem afero.Fs, name string) (afero.File, error) {
	return filesystem.Open(name)
}
