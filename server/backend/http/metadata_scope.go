package fbhttp

import (
	"path/filepath"
	"runtime"
	"strings"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func metadataRealPath(user *users.User, name string) string {
	if user == nil {
		return ""
	}
	switch user.Fs.(type) {
	case *afero.BasePathFs, *files.DriveFs:
		real := user.FullPath(name)
		if filepath.IsAbs(real) {
			return filepath.Clean(real)
		}
	}
	return ""
}

func metadataDescendant(root, candidate string) (string, bool) {
	relative, err := filepath.Rel(root, candidate)
	return relative, err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) && !filepath.IsAbs(relative)
}

func metadataWorkspacePath(user *users.User, real string) (string, bool) {
	root := metadataRealPath(user, "/")
	if root != "" {
		relative, contained := metadataDescendant(root, real)
		if !contained {
			return "", false
		}
		return pathmeta.Clean(filepath.ToSlash(relative)), true
	}
	// A global DriveFs has a virtual '/' spanning drives, without a native
	// root. Verify the inverse mapping rather than treating it as a disk path.
	if _, drive := user.Fs.(*files.DriveFs); drive && runtime.GOOS == "windows" {
		virtual := files.WindowsPathToVirtual(real)
		mapped := metadataRealPath(user, virtual)
		if mapped != "" && strings.EqualFold(mapped, real) {
			return virtual, true
		}
	}
	return "", false
}

// Metadata paths are relative to their owner's current workspace, just like
// resource access. Never compare different users' relative path strings.
func metadataPathMapper(d *data, from, to string) pathmeta.Mapper {
	physicalFrom, physicalTo := metadataRealPath(d.user, from), metadataRealPath(d.user, to)
	owners := map[uint]*users.User{d.user.ID: d.user}
	return func(userID uint, candidate string) (string, bool) {
		if userID == 0 || physicalFrom == "" || physicalTo == "" {
			return candidate, false
		}
		owner, cached := owners[userID]
		if !cached {
			owner, _ = d.store.Users.Get(d.server.Root, userID)
			owners[userID] = owner
		}
		if owner == nil {
			return candidate, false
		}
		physical := metadataRealPath(owner, candidate)
		if physical == "" {
			return candidate, false
		}
		relative, contained := metadataDescendant(physicalFrom, physical)
		if !contained {
			return candidate, false
		}
		rewritten := filepath.Join(physicalTo, relative)
		if next, visible := metadataWorkspacePath(owner, rewritten); visible {
			return next, true
		}
		// The resource moved out of this workspace. Remove its existing row;
		// entering another workspace never creates references there implicitly.
		return "", true
	}
}
