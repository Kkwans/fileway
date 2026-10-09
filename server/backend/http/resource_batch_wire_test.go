package fbhttp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
	"net/http"
	"strings"
	"testing"
)

func TestBatchWireAcknowledgesEveryInputIncludingMissingResources(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/literal%2F.txt", nil, 0600); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, resourceBatchHandler, http.MethodPost, "/resources/batch", bytes.NewBufferString(`{"wirePaths":["/literal%252F.txt","/missing.txt","/literal%252F.txt"]}`), nil)
	var rows []struct {
		Path, WirePath string
		Status         int
	}
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &rows) != nil || len(rows) != 3 {
		t.Fatalf("batch=%d %s", response.Code, response.Body.String())
	}
	for i, want := range []string{"/literal%252F.txt", "/missing.txt", "/literal%252F.txt"} {
		if rows[i].WirePath != want {
			t.Fatalf("index%d ack=%#v", i, rows[i])
		}
	}
	if rows[0].Status != 200 || rows[1].Status != 404 || rows[2].Status != 200 {
		t.Fatalf("partial results=%#v", rows)
	}
}

func TestBatchWireLimitMismatchAndNoContentRead(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/known.txt", []byte("owned bytes must not be read"), 0600); err != nil {
		t.Fatal(err)
	}
	counted := &wireBatchCountingFS{Fs: h.fs[owner.ID]}
	h.fs[owner.ID] = counted
	for _, count := range []int{500, 501} {
		paths := make([]string, count)
		for i := range paths {
			paths[i] = "/known.txt"
		}
		body, _ := json.Marshal(map[string]any{"wirePaths": paths})
		response := h.request(t, owner.ID, resourceBatchHandler, http.MethodPost, "/resources/batch", bytes.NewReader(body), nil)
		want := 200
		if count == 501 {
			want = 400
		}
		if response.Code != want {
			t.Fatalf("count%d status=%d", count, response.Code)
		}
	}
	for _, body := range []string{`{"paths":["/other.txt"],"wirePaths":["/known.txt"]}`, `{"paths":[],"wirePaths":["/known.txt"]}`, `{"wirePaths":["/bad%GG"]}`} {
		response := h.request(t, owner.ID, resourceBatchHandler, http.MethodPost, "/resources/batch", strings.NewReader(body), nil)
		if response.Code != 400 {
			t.Fatalf("bad input=%s status%d", body, response.Code)
		}
	}
	if counted.opens != 0 {
		t.Fatal(fmt.Sprintf("metadata read %d file bodies", counted.opens))
	}
}

type wireBatchCountingFS struct {
	afero.Fs
	opens int
}

func (fs *wireBatchCountingFS) Open(path string) (afero.File, error) {
	fs.opens++
	return fs.Fs.Open(path)
}
