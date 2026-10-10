//go:build !windows

package files

import "github.com/spf13/afero"

func resolvedPrivateResourcePath(_ afero.Fs, _ string) bool { return false }
