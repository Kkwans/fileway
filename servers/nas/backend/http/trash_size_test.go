package fbhttp

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestDeletedDirectorySchedulesSizeButListingDoesNotScan(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Delete: true, Download: true}})
	owner := firstTrashHTTPUser(h)
	fs := h.fs[owner.ID]
	if err := fs.MkdirAll("/docs", 0700); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(fs, "/docs/a", []byte("abc"), 0600); err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	handler := resourceDeleteHandler(noopTrashFileCache{})
	wrapped := func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		d.taskRuntime = runtime
		return handler(w, r, d)
	}
	response := h.request(t, owner.ID, wrapped, http.MethodDelete, "/docs?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("delete: %d %s", response.Code, response.Body.String())
	}
	items, err := h.storage.Trash.List(owner.ID, false)
	if err != nil || len(items) != 1 {
		t.Fatalf("items: %v %v", items, err)
	}
	item := items[0]
	if item.SizeTaskID == "" {
		t.Fatal("deletion did not schedule size")
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		task, err := h.storage.Tasks.Get(owner.ID, item.SizeTaskID, false)
		if err != nil {
			t.Fatal(err)
		}
		if task.Status == tasks.StatusCompleted {
			break
		}
		if task.Status == tasks.StatusFailed || time.Now().After(deadline) {
			t.Fatalf("size task: %+v", task)
		}
		time.Sleep(time.Millisecond)
	}
	if err := afero.WriteFile(fs, item.StoredPath+"/b", []byte("not a trigger"), 0600); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		response = h.request(t, owner.ID, trashListHandler, http.MethodGet, "/api/trash", nil, nil)
		var listed []trash.PublicItem
		if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
			t.Fatal(err)
		}
		if len(listed) != 1 || listed[0].Size != 3 || listed[0].SizeState != trash.SizeAccurate {
			t.Fatalf("list rescanned or lost metadata: %+v", listed)
		}
	}
}

func TestSizeRequestCannotOperateOnAnotherUsersTrash(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner", Perm: users.Permissions{Delete: true}}, users.User{ID: 2, Username: "other", Perm: users.Permissions{Download: true}})
	fs := h.fs[1]
	if err := fs.MkdirAll("/docs", 0700); err != nil {
		t.Fatal(err)
	}
	item, err := (&trash.Service{Fs: fs, Records: h.storage.Trash, Favorites: h.storage.Favorites, Tags: h.storage.Tags}).Move(1, "owner", "/docs")
	if err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, 2, trashSizeHandler(runtime), http.MethodPost, "/api/trash/"+item.ID+"/size", nil, map[string]string{"id": item.ID})
	if response.Code != http.StatusForbidden {
		t.Fatalf("foreign size request: %d", response.Code)
	}
}
