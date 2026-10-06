package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestRecentHTTPRecordsExistingPathsAndStaysPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true}},
		users.User{Username: "member"},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	if err := h.fs[admin.ID].MkdirAll("/docs", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := h.fs[member.ID].MkdirAll("/private", 0o755); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, admin.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/docs"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("record recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, member.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/private"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("list recent status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "/private") || strings.Contains(response.Body.String(), "userId") {
		t.Fatalf("private recent leaked: %s", response.Body.String())
	}
	var entries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &entries); err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Path != "/docs" || !entries[0].IsDir {
		t.Fatalf("admin recent = %#v", entries)
	}

	response = h.request(t, admin.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/missing"}`), nil)
	if response.Code != http.StatusNotFound {
		t.Fatalf("missing recent status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent?limit=invalid", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid limit status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestRecentHTTPUsesRoleAwareRootLabel(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true}},
		users.User{Username: "member"},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")

	for _, userID := range []uint{admin.ID, member.ID} {
		response := h.request(t, userID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/"}`), nil)
		if response.Code != http.StatusOK {
			t.Fatalf("record root status = %d body=%s", response.Code, response.Body.String())
		}
	}

	response := h.request(t, admin.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("admin root list status = %d body=%s", response.Code, response.Body.String())
	}
	var adminEntries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &adminEntries); err != nil {
		t.Fatal(err)
	}
	if len(adminEntries) != 1 || adminEntries[0].Name != "NAS 根目录" {
		t.Fatalf("admin root entry = %#v", adminEntries)
	}

	response = h.request(t, member.ID, recentListHandler, http.MethodGet, "/recent", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member root list status = %d body=%s", response.Code, response.Body.String())
	}
	var memberEntries []*recent.Entry
	if err := json.Unmarshal(response.Body.Bytes(), &memberEntries); err != nil {
		t.Fatal(err)
	}
	if len(memberEntries) != 1 || memberEntries[0].Name != "我的文件" {
		t.Fatalf("member root entry = %#v", memberEntries)
	}
}

func TestRecentPathsFollowRenameAndDisappearInTrash(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Perm: users.Permissions{
			Create: true, Delete: true, Modify: true, Rename: true, Download: true,
		},
	})
	owner := firstTrashHTTPUser(h)
	if err := h.fs[owner.ID].MkdirAll("/docs", 0o755); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, recentRecordHandler, http.MethodPost, "/recent", bytes.NewBufferString(`{"path":"/docs"}`), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("record status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/docs?action=rename&destination=%2Farchive", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("rename status = %d body=%s", response.Code, response.Body.String())
	}
	entries, err := h.storage.Recent.List(owner.ID, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Path != "/archive" || entries[0].Name != "archive" {
		t.Fatalf("recent after rename = %#v", entries)
	}
	response = h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/archive?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("trash status = %d body=%s", response.Code, response.Body.String())
	}
	entries, err = h.storage.Recent.List(owner.ID, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("recent after trash = %#v", entries)
	}
}
