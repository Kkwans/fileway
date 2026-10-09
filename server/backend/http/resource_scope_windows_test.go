//go:build windows

package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestWindowsResourceBoundaryRejectsBackslashRuleAndSiblingScopeAliases(t *testing.T) {
	for _, kind := range []string{"base-path", "scoped-drive"} {
		t.Run(kind, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/",
				Perm:  users.Permissions{Rename: true, Delete: true, Modify: true, Download: true},
				Rules: []rules.Rule{{Path: "/blocked", Allow: false}, {Path: "/�.txt", Allow: false}}})
			owner := firstTrashHTTPUser(h)
			parent := t.TempDir()
			root := filepath.Join(parent, "owned")
			sibling := filepath.Join(parent, "owned-sibling")
			for _, directory := range []string{filepath.Join(root, "blocked"), sibling} {
				if err := os.MkdirAll(directory, 0o700); err != nil {
					t.Fatal(err)
				}
			}
			filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
			if kind == "scoped-drive" {
				filesystem = files.NewScopedDriveFs(files.WindowsPathToVirtual(root))
			}
			h.fs[owner.ID] = filesystem
			attacks := []struct {
				name, source, destination, realSource, realDestination string
			}{
				{"denied-directory", "/blocked\\denied.txt", "/blocked\\renamed.txt",
					filepath.Join(root, "blocked", "denied.txt"), filepath.Join(root, "blocked", "renamed.txt")},
				{"invalid-utf8-unicode-sibling", "/\xff.txt", "/renamed.txt",
					filepath.Join(root, "�.txt"), filepath.Join(root, "renamed.txt")},
			}
			if kind == "base-path" {
				attacks = append(attacks, struct {
					name, source, destination, realSource, realDestination string
				}{"same-prefix-sibling", "/..\\owned-sibling\\outside.txt", "/..\\owned-sibling\\renamed.txt",
					filepath.Join(sibling, "outside.txt"), filepath.Join(sibling, "renamed.txt")})
			}
			for _, attack := range attacks {
				t.Run(attack.name, func(t *testing.T) {
					if err := os.WriteFile(attack.realSource, []byte("protected-owned-fixture"), 0o600); err != nil {
						t.Fatal(err)
					}
					// Prove the alias reaches a real protected file through the
					// current native Fs; the HTTP checker must stop it first.
					if _, err := filesystem.Stat(attack.source); err != nil {
						t.Fatalf("native alias precondition: %v", err)
					}
					wire := files.EncodeWirePath(attack.source)
					for _, method := range []string{http.MethodGet, http.MethodDelete} {
						handler := resourceGetHandler
						if method == http.MethodDelete {
							handler = resourceDeleteHandler(noopTrashFileCache{})
						}
						response := h.request(t, owner.ID, handler, method, wire, nil, nil)
						if response.Code != http.StatusForbidden {
							t.Fatalf("%s alias status=%d body=%s", method, response.Code, response.Body.String())
						}
					}
					patch := wire + "?action=rename&destination=" + url.QueryEscape(attack.destination)
					response := h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, patch, nil, nil)
					if response.Code != http.StatusForbidden {
						t.Fatalf("PATCH alias status=%d body=%s", response.Code, response.Body.String())
					}
					for _, wireOnly := range []bool{false, true} {
						record := recentRecordRequest{Path: attack.source}
						item := batchRenameRequestItem{From: attack.source, To: attack.destination}
						if wireOnly {
							record = recentRecordRequest{WirePath: wire}
							item = batchRenameRequestItem{FromWirePath: wire, ToWirePath: files.EncodeWirePath(attack.destination)}
						}
						body, err := json.Marshal(record)
						if err != nil {
							t.Fatal(err)
						}
						response = h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewReader(body), nil)
						if response.Code != http.StatusForbidden {
							t.Fatalf("recent wireOnly=%t status=%d body=%s", wireOnly, response.Code, response.Body.String())
						}
						body, err = json.Marshal(batchRenameRequest{Items: []batchRenameRequestItem{item}})
						if err != nil {
							t.Fatal(err)
						}
						response = h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
							"/resources/batch-rename", bytes.NewReader(body), nil)
						var result batchRenameResponse
						if response.Code != http.StatusConflict || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Valid || result.Executed {
							t.Fatalf("batch wireOnly=%t status=%d body=%s", wireOnly, response.Code, response.Body.String())
						}
					}
					content, err := os.ReadFile(attack.realSource)
					if err != nil || string(content) != "protected-owned-fixture" {
						t.Fatalf("protected file changed: %q %v", content, err)
					}
					if _, err := os.Stat(attack.realDestination); !os.IsNotExist(err) {
						t.Fatalf("protected destination was created: %v", err)
					}
					entries, err := h.storage.Recent.List(owner.ID, 100)
					if err != nil || len(entries) != 0 {
						t.Fatalf("protected path recorded: %#v %v", entries, err)
					}
				})
			}
		})
	}
}

func TestWindowsResourceBoundaryKeepsCanonicalRootsAndUnicodeSpecialPaths(t *testing.T) {
	for _, kind := range []string{"base-path", "scoped-drive", "global-drive"} {
		t.Run(kind, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/",
				Perm: users.Permissions{Rename: true, Delete: true, Modify: true, Download: true}})
			owner := firstTrashHTTPUser(h)
			root := t.TempDir()
			realSource := filepath.Join(root, "中文 � 100% +#.txt")
			if err := os.WriteFile(realSource, []byte("allowed-owned-fixture"), 0o600); err != nil {
				t.Fatal(err)
			}
			filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
			source := "/中文 � 100% +#.txt"
			roots := []string{"/"}
			switch kind {
			case "scoped-drive":
				filesystem = files.NewScopedDriveFs(files.WindowsPathToVirtual(root))
			case "global-drive":
				filesystem = files.NewDriveFs()
				source = files.WindowsPathToVirtual(realSource)
				roots = append(roots, files.WindowsPathToVirtual(filepath.VolumeName(root)+string(os.PathSeparator)))
			}
			h.fs[owner.ID] = filesystem
			for _, rootPath := range roots {
				response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, files.EncodeWirePath(rootPath)+"?metadata=1", nil, nil)
				if response.Code != http.StatusOK {
					t.Fatalf("root %q status=%d body=%s", rootPath, response.Code, response.Body.String())
				}
			}
			response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, files.EncodeWirePath(source)+"?metadata=1", nil, nil)
			if response.Code != http.StatusOK {
				t.Fatalf("canonical read status=%d body=%s", response.Code, response.Body.String())
			}
			for _, record := range []recentRecordRequest{{Path: source}, {WirePath: files.EncodeWirePath(source)}} {
				body, err := json.Marshal(record)
				if err != nil {
					t.Fatal(err)
				}
				response = h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewReader(body), nil)
				if response.Code != http.StatusOK {
					t.Fatalf("canonical recent status=%d body=%s", response.Code, response.Body.String())
				}
			}
			for index, wireOnly := range []bool{false, true} {
				name := []string{"已改名 100% +#.txt", "最终 100% +#.txt"}[index]
				destination := path.Join(path.Dir(source), name)
				item := batchRenameRequestItem{From: source, To: destination}
				if wireOnly {
					item = batchRenameRequestItem{FromWirePath: files.EncodeWirePath(source), ToWirePath: files.EncodeWirePath(destination)}
				}
				body, err := json.Marshal(batchRenameRequest{Items: []batchRenameRequestItem{item}})
				if err != nil {
					t.Fatal(err)
				}
				response = h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
					"/resources/batch-rename", bytes.NewReader(body), nil)
				var result batchRenameResponse
				if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || !result.Valid || !result.Executed {
					t.Fatalf("canonical batch wireOnly=%t status=%d body=%s", wireOnly, response.Code, response.Body.String())
				}
				source = destination
			}
			finalPath := filepath.Join(root, path.Base(source))
			if content, err := os.ReadFile(finalPath); err != nil || string(content) != "allowed-owned-fixture" {
				t.Fatalf("canonical rename content=%q err=%v", content, err)
			}
			response = h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, files.EncodeWirePath(source), nil, nil)
			if response.Code != http.StatusNoContent {
				t.Fatalf("canonical delete status=%d body=%s", response.Code, response.Body.String())
			}
			if _, err := os.Stat(finalPath); !os.IsNotExist(err) {
				t.Fatalf("canonical delete did not remove file: %v", err)
			}
		})
	}
}
