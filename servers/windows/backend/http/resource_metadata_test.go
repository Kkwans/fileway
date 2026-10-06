package fbhttp

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestResourceMetadataDoesNotExpandDirectory(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Perm:     users.Permissions{Download: true},
	})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/folder/file.txt", []byte("metadata"), 0o640); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, "/folder?metadata=1", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("metadata status = %d body=%s", response.Code, response.Body.String())
	}
	var payload struct {
		Path  string `json:"path"`
		IsDir bool   `json:"isDir"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Path != "/folder" || !payload.IsDir {
		t.Fatalf("metadata payload = %#v", payload)
	}
}
