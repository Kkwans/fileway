package files

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/afero"
)

const UploadPartPrefix = ".fileway-upload-"

// Internal parts never become ordinary preview/search/download resources.
func IsUploadPartPath(value string) bool {
	for _, part := range strings.Split(strings.ReplaceAll(value, "\\", "/"), "/") {
		// Windows resolves aliases case-insensitively; the reserved namespace
		// must not become accessible by changing filename case.
		part = strings.ToLower(part)
		if strings.HasPrefix(part, UploadPartPrefix) && strings.HasSuffix(part, ".part") {
			nonce := strings.TrimSuffix(strings.TrimPrefix(part, UploadPartPrefix), ".part")
			if len(nonce) == 32 && strings.IndexFunc(nonce, func(r rune) bool { return (r < '0' || r > '9') && (r < 'a' || r > 'f') }) < 0 {
				return true
			}
		}
	}
	return false
}

// PublishUpload keeps completed bytes on the destination filesystem. The
// native operation does not replace a target when overwrite was not approved.
func PublishUpload(filesystem afero.Fs, part, target string, replace bool) error {
	from, fromNative := osBackedPath(filesystem, part)
	var to string
	switch fs := filesystem.(type) {
	case *afero.BasePathFs:
		to = filepath.Clean(afero.FullBaseFsPath(fs, target))
	case *afero.OsFs:
		to = filepath.Clean(target)
	case *DriveFs:
		to, _ = fs.RealPath(target)
	}
	toNative := to != ""
	if fromNative && toNative {
		return publishUploadNative(from, to, replace)
	}
	// Non-native filesystems do not expose atomic host primitives. Their caller
	// owns a path lock; never request a silent replacement from that fallback.
	if !replace {
		if _, err := filesystem.Stat(target); err == nil {
			return os.ErrExist
		} else if !os.IsNotExist(err) {
			return err
		}
	}
	return filesystem.Rename(part, target)
}
