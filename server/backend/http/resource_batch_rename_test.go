package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestBatchRenamePreviewAndExecuteCycleWithMetadata(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
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
