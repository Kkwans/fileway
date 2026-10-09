package fbhttp

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

type brokenEditReader struct{}

func (brokenEditReader) Read([]byte) (int, error) { return 0, errors.New("owned interrupted body") }

func TestConditionalTextEditKeepsOriginalOnConflictOrInterruptedBody(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "editor", Perm: users.Permissions{Modify: true}})
	owner := firstTrashHTTPUser(h)
	fs := h.fs[owner.ID]
	if err := afero.WriteFile(fs, "/owned.txt", []byte("original"), 0o640); err != nil {
		t.Fatal(err)
	}
	stat, err := fs.Stat("/owned.txt")
	if err != nil {
		t.Fatal(err)
	}
	endpoint := func(modified time.Time) string {
		return "/owned.txt?conditional=true&expectedSize=" + strconv.FormatInt(stat.Size(), 10) + "&expectedModified=" + url.QueryEscape(modified.Format(time.RFC3339Nano))
	}
	for _, attempt := range []struct {
		route string
		body  io.Reader
		want  int
	}{
		{endpoint(stat.ModTime().Add(-time.Second)), strings.NewReader("wrong version"), http.StatusConflict},
		{endpoint(stat.ModTime()), brokenEditReader{}, http.StatusInternalServerError},
	} {
		response := h.request(t, owner.ID, resourcePutHandler, http.MethodPut, attempt.route, attempt.body, nil)
		if response.Code != attempt.want {
			t.Fatalf("status %d want %d: %s", response.Code, attempt.want, response.Body.String())
		}
		content, err := afero.ReadFile(fs, "/owned.txt")
		if err != nil || string(content) != "original" {
			t.Fatal("failed edit altered original", err)
		}
		entries, err := afero.ReadDir(fs, "/")
		if err != nil || len(entries) != 1 {
			t.Fatal("private part leaked", entries, err)
		}
	}
	for _, replacement := range [][]byte{[]byte("中文\r\n\"new\""), {}} {
		before, err := fs.Stat("/owned.txt")
		if err != nil {
			t.Fatal(err)
		}
		stat = before
		response := h.request(t, owner.ID, resourcePutHandler, http.MethodPut, endpoint(before.ModTime()), bytes.NewReader(replacement), nil)
		content, err := afero.ReadFile(fs, "/owned.txt")
		if response.Code != http.StatusOK || err != nil || !bytes.Equal(content, replacement) {
			t.Fatalf("raw edit failed status=%d content=%q err=%v body=%s", response.Code, content, err, response.Body.String())
		}
	}
}
