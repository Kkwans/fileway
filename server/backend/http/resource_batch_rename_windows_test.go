//go:build windows

package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestWindowsBatchRenameRejectsCaseAliasedSourcesAndDestinationsWithoutMutation(t *testing.T) {
	for _, duplicate := range []string{"sources", "destinations"} {
		t.Run(duplicate, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/", Perm: users.Permissions{Rename: true}})
			owner := firstTrashHTTPUser(h)
			root := t.TempDir()
			h.fs[owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
			for name, content := range map[string]string{"a.txt": "first-owned-content", "b.txt": "second-owned-content"} {
				if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			items := []batchRenameRequestItem{{From: "/a.txt", To: "/same.txt"}, {From: "/b.txt", To: "/SAME.txt"}}
			if duplicate == "sources" {
				items = []batchRenameRequestItem{{From: "/a.txt", To: "/first.txt"}, {From: "/A.txt", To: "/second.txt"}}
			}
			for _, wireOnly := range []bool{false, true} {
				requestItems := append([]batchRenameRequestItem(nil), items...)
				if wireOnly {
					for index, item := range requestItems {
						requestItems[index] = batchRenameRequestItem{FromWirePath: files.EncodeWirePath(item.From), ToWirePath: files.EncodeWirePath(item.To)}
					}
				}
				for _, dryRun := range []bool{true, false} {
					body, err := json.Marshal(batchRenameRequest{Items: requestItems, DryRun: dryRun})
					if err != nil {
						t.Fatal(err)
					}
					response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
						"/resources/batch-rename", bytes.NewReader(body), nil)
					expectedStatus := http.StatusConflict
					if dryRun {
						expectedStatus = http.StatusOK
					}
					var result batchRenameResponse
					if response.Code != expectedStatus || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Valid || result.Executed || len(result.Items) != 2 {
						t.Fatalf("wireOnly=%t dryRun=%t status=%d body=%s", wireOnly, dryRun, response.Code, response.Body.String())
					}
					for _, item := range result.Items {
						if item.Status != "error" || item.Error == "" {
							t.Fatalf("case ambiguity did not identify both conflicting items: %#v", result.Items)
						}
					}
				}
			}
			for name, expected := range map[string]string{"a.txt": "first-owned-content", "b.txt": "second-owned-content"} {
				if content, err := os.ReadFile(filepath.Join(root, name)); err != nil || string(content) != expected {
					t.Fatalf("case ambiguity changed %s: %q %v", name, content, err)
				}
			}
			entries, err := os.ReadDir(root)
			if err != nil || len(entries) != 2 {
				t.Fatalf("case ambiguity created output/temp files: %#v %v", entries, err)
			}
		})
	}
}

func TestWindowsBatchRenameAllowsOneFileToChangeOnlyCase(t *testing.T) {
	for _, wireOnly := range []bool{false, true} {
		name := "json"
		if wireOnly {
			name = "wire"
		}
		t.Run(name, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/", Perm: users.Permissions{Rename: true}})
			owner := firstTrashHTTPUser(h)
			root := t.TempDir()
			h.fs[owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
			if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("case-only-owned-content"), 0o600); err != nil {
				t.Fatal(err)
			}
			item := batchRenameRequestItem{From: "/a.txt", To: "/A.txt"}
			if wireOnly {
				item = batchRenameRequestItem{FromWirePath: "/a.txt", ToWirePath: "/A.txt"}
			}
			for _, dryRun := range []bool{true, false} {
				body, err := json.Marshal(batchRenameRequest{Items: []batchRenameRequestItem{item}, DryRun: dryRun})
				if err != nil {
					t.Fatal(err)
				}
				response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
					"/resources/batch-rename", bytes.NewReader(body), nil)
				var result batchRenameResponse
				if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || !result.Valid || result.Executed == dryRun {
					t.Fatalf("case-only dryRun=%t status=%d body=%s", dryRun, response.Code, response.Body.String())
				}
			}
			if content, err := os.ReadFile(filepath.Join(root, "A.txt")); err != nil || string(content) != "case-only-owned-content" {
				t.Fatalf("case-only rename changed content: %q %v", content, err)
			}
			entries, err := os.ReadDir(root)
			if err != nil || len(entries) != 1 || entries[0].Name() != "A.txt" {
				t.Fatalf("case-only rename did not preserve requested spelling: %#v %v", entries, err)
			}
		})
	}
}
