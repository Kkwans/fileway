//go:build windows

package fbhttp

import (
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestMetadataDriveWorkspaceMapping(t *testing.T) {
	parent := t.TempDir()
	nested := filepath.Join(parent, "nested")
	h := newTrashHTTPHarness(t, users.User{Username: "actor", Scope: "/"}, users.User{Username: "peer", Scope: "/"}, users.User{Username: "other", Scope: "/"})
	var actor, peer, other *users.User
	for _, user := range h.users {
		switch user.Username {
		case "actor":
			actor = user
		case "peer":
			peer = user
		default:
			other = user
		}
	}
	actor.Fs = files.NewDriveFs()
	h.fs[actor.ID] = actor.Fs
	h.fs[peer.ID] = files.NewScopedDriveFs(files.WindowsPathToVirtual(nested))
	h.fs[other.ID] = afero.NewBasePathFs(afero.NewOsFs(), filepath.Join(parent, "unrelated"))
	source := files.WindowsPathToVirtual(filepath.Join(nested, "old.txt"))
	for _, target := range []string{filepath.Join(nested, "new.txt"), filepath.Join(parent, "outside.txt")} {
		virtualTarget := files.WindowsPathToVirtual(target)
		mapper := metadataPathMapper(&data{user: actor, store: h.storage, server: h.server}, source, virtualTarget)
		if got, matched := mapper(actor.ID, source); !matched || got != virtualTarget {
			t.Fatalf("global drive mapping=%q %v want %q", got, matched, virtualTarget)
		}
		expected := "/new.txt"
		if filepath.Dir(target) != nested {
			expected = ""
		}
		if got, matched := mapper(peer.ID, "/old.txt"); !matched || got != expected {
			t.Fatalf("scoped drive mapping=%q %v want %q", got, matched, expected)
		}
		if got, matched := mapper(other.ID, "/old.txt"); matched || got != "/old.txt" {
			t.Fatalf("unrelated native root mapping=%q %v", got, matched)
		}
	}
}
