package fbhttp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestTransferListCursorAndScopedDeleteAll(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{ID: 1, Username: "owner"},
		users.User{ID: 2, Username: "other"},
	)
	owner := h.users[1]
	other := h.users[2]
	for _, input := range []struct {
		user *users.User
		kind transfers.Kind
		name string
	}{
		{owner, transfers.KindUpload, "new"},
		{owner, transfers.KindUpload, "old"},
		{owner, transfers.KindUpload, "oldest"},
		{owner, transfers.KindDownload, "download"},
		{other, transfers.KindUpload, "other"},
	} {
		if _, err := h.storage.Transfers.New(input.user.ID, input.kind, input.name, "/"+input.name, 1); err != nil {
			t.Fatal(err)
		}
	}

	response := h.request(t, owner.ID, transferListHandler, http.MethodGet, "/transfers?kind=upload&limit=2", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("list status = %d body=%s", response.Code, response.Body.String())
	}
	var page transferListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 || page.Total != 3 || page.NextCursor == "" {
		t.Fatalf("first page = %#v", page)
	}
	response = h.request(t, owner.ID, transferListHandler, http.MethodGet, "/transfers?kind=upload&limit=2&cursor="+page.NextCursor, nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("next page status = %d body=%s", response.Code, response.Body.String())
	}
	var next transferListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &next); err != nil {
		t.Fatal(err)
	}
	if len(next.Items) != 1 || next.NextCursor != "" {
		t.Fatalf("next page = %#v", next)
	}

	response = h.request(t, owner.ID, transferDeleteAllHandler, http.MethodDelete, "/transfers?kind=upload", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("delete all status = %d body=%s", response.Code, response.Body.String())
	}
	var deleted transferDeleteAllResponse
	if err := json.Unmarshal(response.Body.Bytes(), &deleted); err != nil {
		t.Fatal(err)
	}
	if deleted.Deleted != 3 {
		t.Fatalf("deleted = %#v", deleted)
	}
	if count, err := h.storage.Transfers.Count(owner.ID, transfers.KindUpload); err != nil || count != 0 {
		t.Fatalf("owner uploads = %d, err=%v", count, err)
	}
	if count, err := h.storage.Transfers.Count(owner.ID, transfers.KindDownload); err != nil || count != 1 {
		t.Fatalf("owner downloads = %d, err=%v", count, err)
	}
	if count, err := h.storage.Transfers.Count(other.ID, transfers.KindUpload); err != nil || count != 1 {
		t.Fatalf("other uploads = %d, err=%v", count, err)
	}
}

func TestTransferListStatusFilter(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner"})
	owner := h.users[1]
	completed, err := h.storage.Transfers.New(owner.ID, transfers.KindUpload, "completed", "/completed", 1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Transfers.SetStatus(completed.ID, owner.ID, transfers.StatusCompleted, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Transfers.New(owner.ID, transfers.KindUpload, "queued", "/queued", 1); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, transferListHandler, http.MethodGet, "/transfers?kind=upload&status=completed", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("filtered list status = %d body=%s", response.Code, response.Body.String())
	}
	var page transferListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if page.Total != 1 || len(page.Items) != 1 || page.Items[0].ID != completed.ID {
		t.Fatalf("filtered page = %#v", page)
	}

	response = h.request(t, owner.ID, transferListHandler, http.MethodGet, "/transfers?status=not-a-status", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid status filter = %d body=%s", response.Code, response.Body.String())
	}
}

func TestUploadBatchMetadataIsStored(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner"})
	owner := h.users[1]
	item, err := h.storage.Transfers.New(owner.ID, transfers.KindUpload, "movie.mkv", "/movie.mkv", 10)
	if err != nil {
		t.Fatal(err)
	}
	metadataRequest := httptest.NewRequest(http.MethodPost, "/upload", nil)
	metadataRequest.Header.Set("X-Upload-Batch-ID", "folder-1")
	metadataRequest.Header.Set("X-Upload-Batch-Name", "%E6%B5%8B%E8%AF%95%E6%96%87%E4%BB%B6%E5%A4%B9")
	metadataRequest.Header.Set("X-Upload-Batch-Items", "4")
	metadataRequest.Header.Set("X-Upload-Batch-Bytes", "1234")
	metadataRequest.Header.Set("X-Upload-Folder", "true")
	if !applyUploadBatchMetadata(item, metadataRequest) {
		t.Fatal("expected metadata to change")
	}
	if item.BatchID != "folder-1" || item.BatchName != "测试文件夹" || item.BatchItems != 4 || item.BatchBytes != 1234 || !item.IsFolderUpload {
		t.Fatalf("metadata = %#v", item)
	}
}
