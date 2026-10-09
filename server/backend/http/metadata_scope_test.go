package fbhttp

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMetadataMutationRespectsWorkspaceIdentity(t *testing.T) {
	for _, operation := range []string{"rename", "move-out", "permanent-delete", "trash-restore"} {
		t.Run(operation, func(t *testing.T) {
			root := t.TempDir()
			h := newTrashHTTPHarness(t,
				users.User{Username: "actor", Scope: filepath.Join(root, "workspace-a"), Perm: users.Permissions{Rename: true, Delete: true, Create: true}},
				users.User{Username: "unrelated", Scope: filepath.Join(root, "workspace-b")},
				users.User{Username: "shared", Scope: filepath.Join(root, "workspace-a", "nested")},
				users.User{Username: "unmapped", Scope: filepath.Join(root, "unknown")})
			byName := make(map[string]*users.User)
			for _, user := range h.users {
				byName[user.Username] = user
			}
			actor := byName["actor"]
			h.fs[byName["unmapped"].ID] = afero.NewMemMapFs()
			source := "/nested/100% 中文.txt"
			if err := afero.WriteFile(h.fs[actor.ID], source, []byte("owned"), 0o600); err != nil {
				t.Fatal(err)
			}
			paths := map[uint]string{actor.ID: source, byName["unrelated"].ID: source, byName["shared"].ID: "/100% 中文.txt", byName["unmapped"].ID: source, 0: source, 999: source}
			favoriteIDs, tagIDs := make(map[uint]string), make(map[uint]string)
			for id, path := range paths {
				favorite, err := h.storage.Favorites.Add(id, path, "owned", 0)
				if err != nil {
					t.Fatal(err)
				}
				favoriteIDs[id] = favorite.ID
				tag, err := h.storage.Tags.Create(id, "owned", "#123456")
				if err != nil {
					t.Fatal(err)
				}
				tagIDs[id] = tag.ID
				if _, err = h.storage.Tags.AddPath(id, tag.ID, path); err != nil {
					t.Fatal(err)
				}
				if _, err = h.storage.Recent.Record(id, path, "owned", false); err != nil {
					t.Fatal(err)
				}
			}
			target := "/nested/renamed.txt"
			if operation == "move-out" {
				target = "/outside.txt"
			}
			if operation == "rename" || operation == "move-out" {
				response := h.request(t, actor.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch,
					files.EncodeWirePath(source)+"?action=rename&destination="+url.QueryEscape(target), nil, nil)
				if response.Code != http.StatusOK {
					t.Fatalf("rename status=%d body=%s", response.Code, response.Body.String())
				}
			} else {
				mode := "permanent"
				if operation == "trash-restore" {
					mode = "trash"
				}
				response := h.request(t, actor.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete,
					files.EncodeWirePath(source)+"?mode="+mode, nil, nil)
				if response.Code != http.StatusOK && response.Code != http.StatusNoContent {
					t.Fatalf("delete status=%d body=%s", response.Code, response.Body.String())
				}
				if operation == "trash-restore" {
					// A different file occupies the old name. Keep-both must restore
					// the shared workspace's metadata to its own relative new name.
					if err := afero.WriteFile(h.fs[actor.ID], source, []byte("new occupant"), 0o600); err != nil {
						t.Fatal(err)
					}
					var item struct {
						ID string `json:"id"`
					}
					if err := json.Unmarshal(response.Body.Bytes(), &item); err != nil {
						t.Fatal(err)
					}
					response = h.request(t, actor.ID, trashRestoreHandler, http.MethodPost, "/trash/"+item.ID+"/restore", bytes.NewBufferString(`{"conflict":"keep-both"}`), map[string]string{"id": item.ID})
					if response.Code != http.StatusOK {
						t.Fatalf("restore status=%d body=%s", response.Code, response.Body.String())
					}
					var restored struct {
						Path string `json:"path"`
					}
					if err := json.Unmarshal(response.Body.Bytes(), &restored); err != nil {
						t.Fatal(err)
					}
					target = restored.Path
				}
			}
			for id, original := range paths {
				expected := original
				if id == actor.ID {
					expected = target
					if operation == "permanent-delete" {
						expected = ""
					}
				}
				if id == byName["shared"].ID {
					expected = target[len("/nested"):]
					if operation == "permanent-delete" || operation == "move-out" {
						expected = ""
					}
				}
				assertWorkspaceMetadata(t, h, id, favoriteIDs[id], tagIDs[id], expected, operation == "trash-restore" && (id == actor.ID || id == byName["shared"].ID))
			}
		})
	}
}

func assertWorkspaceMetadata(t *testing.T, h *trashHTTPHarness, id uint, favoriteID, tagID, expected string, recentRemoved bool) {
	t.Helper()
	favorite, err := h.storage.Favorites.GetByID(id, favoriteID)
	if err != nil && !errors.Is(err, favorites.ErrNotExist) {
		t.Fatal(err)
	}
	if expected == "" {
		if favorite != nil {
			t.Fatalf("user %d favorite still points to %q", id, favorite.Path)
		}
	} else if favorite == nil || favorite.Path != expected {
		t.Fatalf("user %d favorite=%#v want %q", id, favorite, expected)
	}
	tag, err := h.storage.Tags.GetByID(id, tagID)
	if err != nil {
		t.Fatal(err)
	}
	tagged := tag.Paths
	if expected == "" {
		if len(tagged) != 0 {
			t.Fatalf("user %d tag paths=%q want empty", id, tagged)
		}
	} else if len(tagged) != 1 || tagged[0] != expected {
		t.Fatalf("user %d tag paths=%q want [%q]", id, tagged, expected)
	}
	recent, err := h.storage.Recent.List(id, 100)
	if err != nil {
		t.Fatal(err)
	}
	if expected == "" || recentRemoved {
		if len(recent) != 0 {
			t.Fatalf("user %d recent=%#v want empty", id, recent)
		}
	} else if len(recent) != 1 || recent[0].Path != expected {
		t.Fatalf("user %d recent=%#v want %q", id, recent, expected)
	}
}

func TestMetadataMapperDoesNotMergeOpaqueDisplaySiblings(t *testing.T) {
	root := t.TempDir()
	h := newTrashHTTPHarness(t, users.User{Username: "actor", Scope: filepath.Join(root, "shared")}, users.User{Username: "peer", Scope: filepath.Join(root, "shared", "nested")})
	var actor, peer *users.User
	for _, user := range h.users {
		if user.Username == "actor" {
			actor = user
		} else {
			peer = user
		}
	}
	actor.Fs = h.fs[actor.ID]
	mapper := metadataPathMapper(&data{user: actor, store: h.storage, server: h.server}, "/nested/\xd6\xd0.txt", "/nested/新名.txt")
	if got, matched := mapper(peer.ID, "/\xd6\xd0.txt"); !matched || got != "/新名.txt" {
		t.Fatalf("opaque peer mapping=%q %v", got, matched)
	}
	if got, matched := mapper(peer.ID, "/中.txt"); matched || got != "/中.txt" {
		t.Fatalf("UTF-8 sibling was rewritten=%q %v", got, matched)
	}
}
