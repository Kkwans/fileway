package fbhttp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"path"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestBatchRenameWireOnlyKeepsOpaqueSiblingIdentityAndMetadata(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Rename: true}})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	directory := "/\xc4\xbf\xc2\xbc"
	source := directory + "/\xd6\xd0\xce\xc4.txt"
	sibling := directory + "/中文.txt"
	destination := directory + "/new +%?#.txt"
	if err := filesystem.MkdirAll(directory, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string]string{source: "opaque-owned", sibling: "utf8-sibling"} {
		if err := afero.WriteFile(filesystem, name, []byte(content), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	originalRecent, err := h.storage.Recent.Record(owner.ID, source, path.Base(source), false)
	if err != nil {
		t.Fatal(err)
	}
	siblingRecent, err := h.storage.Recent.Record(owner.ID, sibling, path.Base(sibling), false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Favorites.Add(owner.ID, source, path.Base(source), 0); err != nil {
		t.Fatal(err)
	}
	tag, err := h.storage.Tags.Create(owner.ID, "opaque-rename", "#1677ff")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Tags.AddPath(owner.ID, tag.ID, source); err != nil {
		t.Fatal(err)
	}
	// Linux can address these bytes exactly. Windows UTF-16 cannot, so the
	// same public contract must reject the request without changing metadata.
	unsupported := runtime.GOOS == "windows"
	for _, dryRun := range []bool{true, false} {
		body, err := json.Marshal(map[string]interface{}{"dryRun": dryRun, "items": []map[string]string{{
			"fromWirePath": files.EncodeWirePath(source), "toWirePath": files.EncodeWirePath(destination),
		}}})
		if err != nil {
			t.Fatal(err)
		}
		response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewReader(body), nil)
		expectedStatus := http.StatusOK
		if unsupported && !dryRun {
			expectedStatus = http.StatusConflict
		}
		if response.Code != expectedStatus {
			t.Fatalf("dryRun=%t status=%d body=%s", dryRun, response.Code, response.Body.String())
		}
		var result struct {
			Valid    bool `json:"valid"`
			Executed bool `json:"executed"`
			Items    []struct {
				From         string `json:"from"`
				To           string `json:"to"`
				FromWirePath string `json:"fromWirePath"`
				ToWirePath   string `json:"toWirePath"`
				Status       string `json:"status"`
			} `json:"items"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if result.Valid == unsupported || result.Executed != (!dryRun && !unsupported) || len(result.Items) != 1 {
			t.Fatalf("response = %#v", result)
		}
		item := result.Items[0]
		if item.From != files.DisplayPath(source) || item.To != files.DisplayPath(destination) || item.FromWirePath != files.EncodeWirePath(source) || item.ToWirePath != files.EncodeWirePath(destination) {
			t.Fatalf("raw identity changed: %#v", item)
		}
		if unsupported && item.Status != "error" || !unsupported && ((dryRun && item.Status != "ready") || (!dryRun && item.Status != "completed")) {
			t.Fatalf("incorrect status: %#v", item)
		}
		currentPath := destination
		if dryRun || unsupported {
			currentPath = source
			if exists, _ := afero.Exists(filesystem, destination); exists {
				t.Fatal("dry-run created destination")
			}
		}
		if content, err := afero.ReadFile(filesystem, currentPath); err != nil || string(content) != "opaque-owned" {
			t.Fatalf("source content changed: %q %v", content, err)
		}
		if content, err := afero.ReadFile(filesystem, sibling); err != nil || string(content) != "utf8-sibling" {
			t.Fatalf("same-display sibling changed: %q %v", content, err)
		}
		entries, err := h.storage.Recent.List(owner.ID, 100)
		if err != nil || len(entries) != 2 {
			t.Fatalf("recent metadata = %#v %v", entries, err)
		}
		for _, entry := range entries {
			if entry.ID == originalRecent.ID && entry.Path != currentPath {
				t.Fatalf("source metadata points at %q", entry.Path)
			}
			if entry.ID == siblingRecent.ID && entry.Path != sibling {
				t.Fatalf("sibling metadata changed to %q", entry.Path)
			}
		}
	}
	if exists, _ := afero.Exists(filesystem, source); exists != unsupported {
		t.Fatalf("source presence=%t, Windows rejection=%t", exists, unsupported)
	}
	expectedPath := destination
	if unsupported {
		expectedPath = source
	}
	favorites, err := h.storage.Favorites.GetAll(owner.ID)
	if err != nil || len(favorites) != 1 || favorites[0].Path != expectedPath {
		t.Fatalf("favorites = %#v %v", favorites, err)
	}
	tags, err := h.storage.Tags.GetAll(owner.ID)
	if err != nil || len(tags) != 1 || len(tags[0].Paths) != 1 || tags[0].Paths[0] != expectedPath {
		t.Fatalf("tags = %#v %v", tags, err)
	}
}

func TestBatchRenameMalformedWireAndDisplayConflictNeverMutate(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Rename: true}})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	if err := afero.WriteFile(filesystem, "/source", []byte("owned"), 0o640); err != nil {
		t.Fatal(err)
	}
	for _, item := range []map[string]string{
		{"fromWirePath": "/source%2Fchild", "toWirePath": "/target"},
		{"fromWirePath": "/source", "toWirePath": "/target%00"},
		{"fromWirePath": "/bad%", "toWirePath": "/target"},
		{"fromWirePath": "/bad%GG", "toWirePath": "/target"},
		{"from": "/other", "fromWirePath": "/source", "toWirePath": "/target"},
		{"fromWirePath": "/source", "to": "/other", "toWirePath": "/target"},
		{"fromWirePath": "//host/source", "toWirePath": "/target"},
		{"from": "/source", "to": "/target\x00"},
	} {
		for _, dryRun := range []bool{true, false} {
			body, _ := json.Marshal(map[string]interface{}{"items": []map[string]string{item}, "dryRun": dryRun})
			response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewReader(body), nil)
			expectedStatus := http.StatusConflict
			if dryRun {
				expectedStatus = http.StatusOK
			}
			var result batchRenameResponse
			if response.Code != expectedStatus || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Valid || result.Executed {
				t.Fatalf("invalid item=%#v dryRun=%t status=%d body=%s", item, dryRun, response.Code, response.Body.String())
			}
			if content, err := afero.ReadFile(filesystem, "/source"); err != nil || string(content) != "owned" {
				t.Fatalf("invalid request changed source: %q %v", content, err)
			}
			listing, err := afero.ReadDir(filesystem, "/")
			if err != nil || len(listing) != 1 {
				t.Fatalf("invalid request left artifacts: %#v %v", listing, err)
			}
		}
	}
}

func TestBatchRenameWirePathStillEnforcesRenameAndPathPermissions(t *testing.T) {
	for _, rename := range []bool{false, true} {
		t.Run(fmt.Sprintf("rename=%t", rename), func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Rename: rename}, Rules: []rules.Rule{{Path: "/blocked", Allow: false}}})
			owner := firstTrashHTTPUser(h)
			if err := afero.WriteFile(h.fs[owner.ID], "/blocked", []byte("owned"), 0o640); err != nil {
				t.Fatal(err)
			}
			response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewBufferString(`{"dryRun":true,"items":[{"fromWirePath":"/blocked","toWirePath":"/target"}]}`), nil)
			if !rename {
				if response.Code != http.StatusForbidden {
					t.Fatalf("rename permission bypass: %d", response.Code)
				}
			} else {
				var result batchRenameResponse
				if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.Valid || result.Executed {
					t.Fatalf("path rule bypass: %d %s", response.Code, response.Body.String())
				}
			}
		})
	}
}

func TestBatchRenamePreviewAndExecuteCycleWithMetadata(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Scope:    filepath.Join(t.TempDir(), "owner"),
		Perm:     users.Permissions{Create: true, Delete: true, Modify: true, Rename: true, Download: true},
	})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	if err := afero.WriteFile(filesystem, "/a.txt", []byte("A"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, "/b.txt", []byte("B"), 0o640); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Favorites.Add(owner.ID, "/a.txt", "a.txt", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Recent.Record(owner.ID, "/a.txt", "a.txt", false); err != nil {
		t.Fatal(err)
	}
	tag, err := h.storage.Tags.Create(owner.ID, "swap", "#1677ff")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Tags.AddPath(owner.ID, tag.ID, "/a.txt"); err != nil {
		t.Fatal(err)
	}

	request := `{"dryRun":true,"items":[{"from":"/a.txt","to":"/b.txt"},{"from":"/b.txt","to":"/a.txt"}]}`
	response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewBufferString(request), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("preview status = %d body=%s", response.Code, response.Body.String())
	}
	var preview batchRenameResponse
	if err := json.Unmarshal(response.Body.Bytes(), &preview); err != nil {
		t.Fatal(err)
	}
	if !preview.Valid || preview.Executed {
		t.Fatalf("preview = %#v", preview)
	}
	if content, _ := afero.ReadFile(filesystem, "/a.txt"); string(content) != "A" {
		t.Fatalf("dry-run changed a.txt to %q", content)
	}

	request = `{"items":[{"from":"/a.txt","to":"/b.txt"},{"from":"/b.txt","to":"/a.txt"}]}`
	response = h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewBufferString(request), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("execute status = %d body=%s", response.Code, response.Body.String())
	}
	if content, _ := afero.ReadFile(filesystem, "/a.txt"); string(content) != "B" {
		t.Fatalf("a.txt after swap = %q", content)
	}
	if content, _ := afero.ReadFile(filesystem, "/b.txt"); string(content) != "A" {
		t.Fatalf("b.txt after swap = %q", content)
	}
	favorites, err := h.storage.Favorites.GetAll(owner.ID)
	if err != nil || len(favorites) != 1 || favorites[0].Path != "/b.txt" {
		t.Fatalf("favorites after swap = %#v err=%v", favorites, err)
	}
	recentEntries, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil || len(recentEntries) != 1 || recentEntries[0].Path != "/b.txt" {
		t.Fatalf("recent after swap = %#v err=%v", recentEntries, err)
	}
	tags, err := h.storage.Tags.GetAll(owner.ID)
	if err != nil || len(tags) != 1 || len(tags[0].Paths) != 1 || tags[0].Paths[0] != "/b.txt" {
		t.Fatalf("tags after swap = %#v err=%v", tags, err)
	}
}

func TestBatchRenameRejectsConflictDuplicateAndInvalidRequestsWithoutMutation(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Perm:     users.Permissions{Rename: true},
	})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	for name, content := range map[string]string{"/a.txt": "A", "/existing.txt": "E"} {
		if err := afero.WriteFile(filesystem, name, []byte(content), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	request := `{"dryRun":true,"items":[{"from":"/a.txt","to":"/existing.txt"},{"from":"/a.txt","to":"/same.txt"},{"from":"/a.txt","to":"/same.txt"}]}`
	response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewBufferString(request), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("invalid preview status = %d body=%s", response.Code, response.Body.String())
	}
	var preview batchRenameResponse
	if err := json.Unmarshal(response.Body.Bytes(), &preview); err != nil {
		t.Fatal(err)
	}
	if preview.Valid {
		t.Fatalf("invalid preview accepted: %#v", preview)
	}
	if content, _ := afero.ReadFile(filesystem, "/a.txt"); string(content) != "A" {
		t.Fatalf("invalid preview changed source to %q", content)
	}

	response = h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost, "/resources/batch-rename", bytes.NewBufferString(`{"items":[{"from":"/a.txt","to":"/a.txt"}]}`), nil)
	if response.Code != http.StatusConflict {
		t.Fatalf("invalid execute status = %d body=%s", response.Code, response.Body.String())
	}
	if exists, _ := afero.Exists(filesystem, "/a.txt"); !exists {
		t.Fatal("invalid execution removed source")
	}
}
