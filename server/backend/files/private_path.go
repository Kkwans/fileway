package files

import "github.com/spf13/afero"

// IsPrivateResourcePath keeps upload/transaction parts inaccessible even when
// Windows resolves a current filesystem path through an existing short alias.
// Non-Windows filenames retain the original lexical/raw-byte contract.
func IsPrivateResourcePath(filesystem afero.Fs, name string) bool {
	if IsUploadPartPath(name) {
		return true
	}
	return resolvedPrivateResourcePath(filesystem, name)
}
