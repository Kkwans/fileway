package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestFavoritesWirePathsKeepSameDisplayResourcesDistinct(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows filenames cannot contain non-UTF-8 source bytes")
	}
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	paths := []string{"/\xd6\xd0\xce\xc4.txt", "/中文.txt"}
	ids := make([]string, 0, 2)
	for index, path := range paths {
		if err := afero.WriteFile(h.fs[owner.ID], path, []byte{byte(index)}, 0o600); err != nil {
			t.Fatal(err)
		}
		body, _ := json.Marshal(map[string]string{"wirePath": files.EncodeWirePath(path), "name": "中文.txt"})
		response := h.request(t, owner.ID, favoritesPostHandler, http.MethodPost, "/favorites", bytes.NewReader(body), nil)
		if response.Code != http.StatusOK {
			t.Fatalf("wire create status=%d body=%s", response.Code, response.Body.String())
		}
		var row map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &row); err != nil {
			t.Fatal(err)
		}
		if row["path"] != "/中文.txt" || row["wirePath"] != files.EncodeWirePath(path) || row["pathVerified"] != true {
			t.Fatalf("wire acknowledgement=%#v", row)
		}
		ids = append(ids, row["id"].(string))
	}
	if ids[0] == ids[1] {
		t.Fatal("same-display source bytes were merged")
	}
	response := h.request(t, owner.ID, favoritesGetHandler, http.MethodGet, "/favorites", nil, nil)
	var rows []struct {
		ID, Path, WirePath string
		PathVerified       bool
	}
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &rows) != nil || len(rows) != 2 {
		t.Fatalf("wire list=%d %s", response.Code, response.Body.String())
	}
	for _, row := range rows {
		response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, row.WirePath+"?metadata=1", nil, nil)
		var metadata struct{ WirePath string }
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &metadata) != nil || metadata.WirePath != row.WirePath {
			t.Fatalf("favorite could not resolve original resource: %d %s", response.Code, response.Body.String())
		}
		response = h.request(t, owner.ID, rawHandler, http.MethodGet, row.WirePath, nil, nil)
		expected := byte(0)
		if row.ID == ids[1] {
			expected = 1
		}
		if response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), []byte{expected}) {
			t.Fatalf("favorite opened another same-display file: id=%s wire=%s status=%d bytes=%x", row.ID, row.WirePath, response.Code, response.Body.Bytes())
		}
	}
	response = h.request(t, owner.ID, favoriteDeleteHandler, http.MethodDelete, "/favorites/"+ids[0], nil, map[string]string{"id": ids[0]})
	if response.Code != http.StatusOK {
		t.Fatal(response.Code)
	}
	remaining, err := h.storage.Favorites.GetAll(owner.ID)
	if err != nil || len(remaining) != 1 || remaining[0].ID != ids[1] || remaining[0].Path != paths[1] {
		t.Fatalf("removed wrong association: %#v %v", remaining, err)
	}
	for index, path := range paths {
		content, err := afero.ReadFile(h.fs[owner.ID], path)
		if err != nil || !bytes.Equal(content, []byte{byte(index)}) {
			t.Fatalf("favorite deletion changed source %q", path)
		}
	}
}

func TestFavoritesWireInputValidationAndUTF8LegacyCompatibility(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Rules: []rules.Rule{{Path: "/denied.txt", Allow: false}}})
	owner := firstTrashHTTPUser(h)
	for _, name := range []string{"/100% +?#中文.txt", "/literal%2F.txt", "/denied.txt"} {
		if err := afero.WriteFile(h.fs[owner.ID], name, nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, test := range []struct {
		body   map[string]string
		status int
	}{
		{map[string]string{"path": "/100% +?#中文.txt", "wirePath": files.EncodeWirePath("/100% +?#中文.txt"), "name": "one"}, 200},
		{map[string]string{"path": "/literal%2F.txt", "name": "legacy"}, 200},
		{map[string]string{"path": "/literal%2F.txt", "wirePath": "/different.txt"}, 400},
		{map[string]string{"wirePath": "/bad%GG"}, 400},
		{map[string]string{"wirePath": "/a%2Fb"}, 400},
		{map[string]string{"wirePath": "/bad%00"}, 400},
		{map[string]string{"wirePath": "/denied.txt"}, 403},
		{map[string]string{"wirePath": "/missing.txt"}, 404},
	} {
		body, _ := json.Marshal(test.body)
		response := h.request(t, owner.ID, favoritesPostHandler, http.MethodPost, "/favorites", bytes.NewReader(body), nil)
		if response.Code != test.status {
			t.Fatalf("input=%#v status=%d want=%d body=%s", test.body, response.Code, test.status, response.Body.String())
		}
	}
	rows, err := h.storage.Favorites.GetAll(owner.ID)
	if err != nil || len(rows) != 2 {
		t.Fatalf("rejected paths created favorite rows=%#v %v", rows, err)
	}
}
