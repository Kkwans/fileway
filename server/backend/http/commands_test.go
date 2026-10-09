package fbhttp

import (
	"net/http"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestCommandDirectoryIsCheckedBeforeWebSocketOrProcessStart(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Execute: true}, Rules: []rules.Rule{{Path: "/blocked", Allow: false}}})
	owner := firstTrashHTTPUser(h)
	if err := h.fs[owner.ID].MkdirAll("/blocked", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs[owner.ID], "/owned.txt", []byte("owned"), 0o640); err != nil {
		t.Fatal(err)
	}
	for _, attempt := range []struct {
		path   string
		status int
	}{{"/blocked", http.StatusForbidden}, {"/missing", http.StatusNotFound}, {"/owned.txt", http.StatusBadRequest}} {
		response := h.request(t, owner.ID, commandsHandler, http.MethodGet, attempt.path, nil, nil)
		if response.Code != attempt.status {
			t.Fatalf("cwd=%s status=%d want=%d body=%s", attempt.path, response.Code, attempt.status, response.Body.String())
		}
	}
}
