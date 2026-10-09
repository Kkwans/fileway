package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/gorilla/mux"
	"github.com/spf13/afero"
)

func TestArchiveEntryDownloadOnlyPreparationRealRangeAndRepeatedAuthorization(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "reader", Perm: users.Permissions{Download: true}}, users.User{Username: "other", Perm: users.Permissions{Download: true}})
	reader := trashHTTPUserByName(t, h, "reader")
	other := trashHTTPUserByName(t, h, "other")
	writeTarFixture(t, h.fs[reader.ID], "/bundle.tar", map[string]string{"movie.mp4": "0123456789", "skip.txt": "unselected"})
	info, err := h.fs[reader.ID].Stat("/bundle.tar")
	if err != nil {
		t.Fatal(err)
	}
	work := t.TempDir()
	cache, err := archivefs.NewEntryCache(archivefs.EntryCacheConfig{WorkDir: work, MaxBytes: 1024})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cache.Close() }()
	body, _ := json.Marshal(map[string]interface{}{"archiveWirePath": "/bundle.tar", "entryWirePath": "movie.mp4", "sourceSize": info.Size(), "sourceModified": info.ModTime().UnixMilli()})
	response := h.request(t, reader.ID, archiveEntryPrepareHandler(cache), http.MethodPost, "/archives/open", bytes.NewReader(body), nil)
	var state archiveEntryStatus
	if response.Code != http.StatusAccepted || json.Unmarshal(response.Body.Bytes(), &state) != nil || strings.Contains(response.Body.String(), work) {
		t.Fatalf("prepare=%d %s", response.Code, response.Body.String())
	}
	deadline := time.Now().Add(5 * time.Second)
	for state.State != "ready" && time.Now().Before(deadline) {
		response = h.request(t, reader.ID, archiveEntryStatusHandler(cache), http.MethodGet, "/archives/open/"+state.ID, nil, map[string]string{"id": state.ID})
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &state) != nil || state.State == "failed" {
			t.Fatalf("status=%d %s", response.Code, response.Body.String())
		}
		time.Sleep(time.Millisecond)
	}
	if state.State != "ready" {
		t.Fatal("preparation did not finish")
	}
	request := httptest.NewRequest(http.MethodGet, "/archives/open/"+state.ID+"/content", nil)
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, reader.ID))
	request.Header.Set("Range", "bytes=2-5")
	request = mux.SetURLVars(request, map[string]string{"id": state.ID})
	ranged := httptest.NewRecorder()
	handle(archiveEntryContentHandler(cache), "", h.storage, h.server).ServeHTTP(ranged, request)
	if ranged.Code != http.StatusPartialContent || ranged.Body.String() != "2345" || ranged.Header().Get("Content-Range") != "bytes 2-5/10" || ranged.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatalf("not real Range: %d %v %q", ranged.Code, ranged.Header(), ranged.Body.String())
	}
	response = h.request(t, reader.ID, archiveEntryContentHandler(cache), http.MethodHead, "/archives/open/"+state.ID+"/content", nil, map[string]string{"id": state.ID})
	if response.Code != http.StatusOK || response.Body.Len() != 0 || response.Header().Get("Content-Length") != "10" {
		t.Fatal("HEAD did not describe prepared bytes", response.Code, response.Header())
	}
	response = h.request(t, other.ID, archiveEntryContentHandler(cache), http.MethodGet, "/archives/open/"+state.ID+"/content", nil, map[string]string{"id": state.ID})
	if response.Code != http.StatusForbidden {
		t.Fatal("cross-account content accepted", response.Code)
	}
	reader.Perm.Download = false
	if err := h.storage.Users.Update(reader, "Perm"); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, reader.ID, archiveEntryContentHandler(cache), http.MethodGet, "/archives/open/"+state.ID+"/content", nil, map[string]string{"id": state.ID})
	if response.Code != http.StatusForbidden {
		t.Fatal("revoked Download still served", response.Code)
	}
	reader.Perm.Download = true
	if err := h.storage.Users.Update(reader, "Perm"); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs[reader.ID], "/bundle.tar", []byte("changed"), 0o600); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, reader.ID, archiveEntryContentHandler(cache), http.MethodGet, "/archives/open/"+state.ID+"/content", nil, map[string]string{"id": state.ID})
	if response.Code != http.StatusConflict {
		t.Fatal("changed archive still served", response.Code)
	}
	rows, err := afero.ReadDir(h.fs[reader.ID], "/")
	if err != nil || len(rows) != 1 {
		t.Fatalf("user Fs was written: %#v %v", rows, err)
	}
}
