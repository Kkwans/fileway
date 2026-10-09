package fbhttp

import (
	"bytes"
	"encoding/json"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
	"net/http"
	"runtime"
	"testing"
)

func TestTagsWireReferencesRemainIndependentAndCanRemoveMissingResource(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("non-UTF8 names require Linux")
	}
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	tag, err := h.storage.Tags.Create(owner.ID, "owned", "#123456")
	if err != nil {
		t.Fatal(err)
	}
	paths := []string{"/\xd6\xd0\xce\xc4.txt", "/中文.txt"}
	for i, p := range paths {
		if err := afero.WriteFile(h.fs[owner.ID], p, []byte{byte(i)}, 0600); err != nil {
			t.Fatal(err)
		}
		body, _ := json.Marshal(map[string]string{"wirePath": files.EncodeWirePath(p)})
		response := h.request(t, owner.ID, tagAddPathHandler, http.MethodPost, "/tags/"+tag.ID+"/paths", bytes.NewReader(body), map[string]string{"id": tag.ID})
		if response.Code != 200 {
			t.Fatalf("wire association status=%d body=%s", response.Code, response.Body.String())
		}
	}
	response := h.request(t, owner.ID, tagsGetHandler, http.MethodGet, "/tags", nil, nil)
	var rows []struct {
		PathRefs []struct {
			Path, WirePath string
			PathVerified   bool
		}
	}
	if json.Unmarshal(response.Body.Bytes(), &rows) != nil || len(rows) != 1 || len(rows[0].PathRefs) != 2 {
		t.Fatalf("refs=%s", response.Body.String())
	}
	for i, ref := range rows[0].PathRefs {
		if ref.Path != "/中文.txt" || !ref.PathVerified || ref.WirePath != files.EncodeWirePath(paths[i]) {
			t.Fatalf("ref=%#v", ref)
		}
		raw := h.request(t, owner.ID, rawHandler, http.MethodGet, ref.WirePath, nil, nil)
		if raw.Code != 200 || !bytes.Equal(raw.Body.Bytes(), []byte{byte(i)}) {
			t.Fatalf("opened wrong source=%x", raw.Body.Bytes())
		}
	}
	if err := h.fs[owner.ID].Remove(paths[0]); err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]string{"wirePath": files.EncodeWirePath(paths[0])})
	response = h.request(t, owner.ID, tagRemovePathHandler, http.MethodDelete, "/tags/"+tag.ID+"/paths", bytes.NewReader(body), map[string]string{"id": tag.ID})
	if response.Code != 200 {
		t.Fatalf("unlink missing source=%d %s", response.Code, response.Body.String())
	}
	saved, err := h.storage.Tags.GetByID(owner.ID, tag.ID)
	if err != nil || len(saved.Paths) != 1 || saved.Paths[0] != paths[1] {
		t.Fatalf("wrong sibling unlinked=%#v %v", saved, err)
	}
}

func TestTagWireValidationPermissionsAndLegacyUTF8Aliases(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Rules: []rules.Rule{{Path: "/denied.txt", Allow: false}}}, users.User{Username: "other"})
	var owner, other *users.User
	for _, user := range h.users {
		if user.Username == "owner" {
			owner = user
		} else {
			other = user
		}
	}
	tag, err := h.storage.Tags.Create(owner.ID, "Owned tag", "#123456")
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/literal%2F.txt", "/denied.txt"} {
		if err := afero.WriteFile(h.fs[owner.ID], path, nil, 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, item := range []struct {
		body   string
		status int
	}{
		{`{"path":"/literal%2F.txt","wirePath":"/literal%252F.txt"}`, 200},
		{`{"path":"/literal%2F.txt","wirePath":"/other.txt"}`, 400},
		{`{"wirePath":"/denied.txt"}`, 403}, {`{"wirePath":"/missing.txt"}`, 404}, {`{"wirePath":"/bad%00"}`, 400},
	} {
		response := h.request(t, owner.ID, tagAddPathHandler, http.MethodPost, "/tags/"+tag.ID+"/paths", bytes.NewBufferString(item.body), map[string]string{"id": tag.ID})
		if response.Code != item.status {
			t.Fatalf("input%s status%d want%d", item.body, response.Code, item.status)
		}
	}
	response := h.request(t, other.ID, tagRemovePathHandler, http.MethodDelete, "/tags/"+tag.ID+"/paths", bytes.NewBufferString(`{"wirePath":"/literal%252F.txt"}`), map[string]string{"id": tag.ID})
	if response.Code != 404 {
		t.Fatalf("foreign tag unlink=%d", response.Code)
	}
	// Canonical aliases do not create a duplicate association and wire unlink
	// leaves the literal percent filename intact.
	if _, err := h.storage.Tags.AddPath(owner.ID, tag.ID, "//literal%2F.txt"); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, tagRemovePathHandler, http.MethodDelete, "/tags/"+tag.ID+"/paths", bytes.NewBufferString(`{"wirePath":"/literal%252F.txt"}`), map[string]string{"id": tag.ID})
	if response.Code != 200 {
		t.Fatal(response.Code)
	}
	saved, _ := h.storage.Tags.GetByID(owner.ID, tag.ID)
	if len(saved.Paths) != 0 {
		t.Fatalf("legacy alias was not removed: %#v", saved.Paths)
	}
}
