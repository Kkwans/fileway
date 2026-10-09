//go:build linux

package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestLinuxResourceBoundaryKeepsLiteralBackslashFileNames(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/", Perm: users.Permissions{Rename: true, Download: true}})
	owner := firstTrashHTTPUser(h)
	root := t.TempDir()
	h.fs[owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
	source := "/literal\\中文 100% +?#.txt"
	if err := os.WriteFile(filepath.Join(root, source[1:]), []byte("literal-backslash"), 0o600); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, files.EncodeWirePath(source)+"?metadata=1", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("literal filename read status=%d body=%s", response.Code, response.Body.String())
	}
	for _, record := range []recentRecordRequest{{Path: source}, {WirePath: files.EncodeWirePath(source)}} {
		body, err := json.Marshal(record)
		if err != nil {
			t.Fatal(err)
		}
		response = h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewReader(body), nil)
		if response.Code != http.StatusOK {
			t.Fatalf("literal filename recent status=%d body=%s", response.Code, response.Body.String())
		}
	}
	destination := "/renamed\\中文 100% +?#.txt"
	body, err := json.Marshal(batchRenameRequest{Items: []batchRenameRequestItem{{
		FromWirePath: files.EncodeWirePath(source), ToWirePath: files.EncodeWirePath(destination),
	}}})
	if err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
		"/resources/batch-rename", bytes.NewReader(body), nil)
	var result batchRenameResponse
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || !result.Valid || !result.Executed {
		t.Fatalf("literal filename batch status=%d body=%s", response.Code, response.Body.String())
	}
	if content, err := os.ReadFile(filepath.Join(root, destination[1:])); err != nil || string(content) != "literal-backslash" {
		t.Fatalf("literal filename rename content=%q err=%v", content, err)
	}
}

func TestLinuxBatchRenameKeepsCaseDistinctSourcesAndDestinations(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: "/", Perm: users.Permissions{Rename: true}})
	owner := firstTrashHTTPUser(h)
	root := t.TempDir()
	h.fs[owner.ID] = afero.NewBasePathFs(afero.NewOsFs(), root)
	for name, content := range map[string]string{"a.txt": "lowercase-owned", "A.txt": "uppercase-owned"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	body, err := json.Marshal(batchRenameRequest{Items: []batchRenameRequestItem{
		{From: "/a.txt", To: "/same.txt"}, {From: "/A.txt", To: "/SAME.txt"},
	}})
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, resourceBatchRenameHandler(noopTrashFileCache{}), http.MethodPost,
		"/resources/batch-rename", bytes.NewReader(body), nil)
	var result batchRenameResponse
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || !result.Valid || !result.Executed {
		t.Fatalf("case-distinct batch status=%d body=%s", response.Code, response.Body.String())
	}
	for name, expected := range map[string]string{"same.txt": "lowercase-owned", "SAME.txt": "uppercase-owned"} {
		if content, err := os.ReadFile(filepath.Join(root, name)); err != nil || string(content) != expected {
			t.Fatalf("case-distinct %s content=%q err=%v", name, content, err)
		}
	}
}
