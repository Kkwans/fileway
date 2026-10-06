package fbhttp

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestTransferTrackerRecordsHTTPFailureInsteadOfCompletion(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		ID:       1,
		Username: "owner",
		Perm:     users.Permissions{Download: true},
	})
	owner := firstTrashHTTPUser(h)
	item, err := h.storage.Transfers.New(owner.ID, transfers.KindDownload, "missing.txt", "/missing.txt", 12)
	if err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodGet, "/missing.txt?transfer="+item.ID, nil)
	recorder := httptest.NewRecorder()
	tracker := &transferTracker{
		ResponseWriter: recorder,
		request:        request,
		data:           &data{store: h.storage, user: owner},
		item:           item,
	}
	tracker.WriteHeader(http.StatusNotFound)
	tracker.finish(0, nil)
	tracker.finish(http.StatusOK, nil)

	stored, err := h.storage.Transfers.Get(owner.ID, item.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != transfers.StatusFailed {
		t.Fatalf("status = %q, want failed", stored.Status)
	}
	if stored.Error != http.StatusText(http.StatusNotFound) {
		t.Fatalf("error = %q, want %q", stored.Error, http.StatusText(http.StatusNotFound))
	}
}

func TestTransferTrackerMarksSuccessfulResponseCompleted(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		ID:       2,
		Username: "owner",
		Perm:     users.Permissions{Download: true},
	})
	owner := firstTrashHTTPUser(h)
	item, err := h.storage.Transfers.New(owner.ID, transfers.KindDownload, "ok.txt", "/ok.txt", 4)
	if err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodGet, "/ok.txt?transfer="+item.ID, nil)
	recorder := httptest.NewRecorder()
	tracker := &transferTracker{
		ResponseWriter: recorder,
		request:        request,
		data:           &data{store: h.storage, user: owner},
		item:           item,
	}
	if _, err := tracker.Write([]byte("done")); err != nil {
		t.Fatal(err)
	}
	tracker.finish(0, nil)

	stored, err := h.storage.Transfers.Get(owner.ID, item.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != transfers.StatusCompleted {
		t.Fatalf("status = %q, want completed", stored.Status)
	}
	if stored.BytesTransferred != stored.BytesTotal {
		t.Fatalf("bytes transferred = %d, want %d", stored.BytesTransferred, stored.BytesTotal)
	}
}
