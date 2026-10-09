package fbhttp

import (
	"archive/zip"
	"bytes"
	"io"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestRawZIPWireSelectionKeepsCommaLiteralPercentAndSingleFile(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := h.fs[owner.ID].MkdirAll("/docs", 0o750); err != nil {
		t.Fatal(err)
	}
	for name, content := range map[string]string{"comma,name.txt": "comma-owned", "100% +?#.txt": "literal-owned", "not-selected.txt": "private-unselected"} {
		if err := afero.WriteFile(h.fs[owner.ID], "/docs/"+name, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, names := range [][]string{{"comma,name.txt"}, {"comma,name.txt", "100% +?#.txt"}} {
		query := url.Values{"algo": {"zip"}}
		for _, name := range names {
			query.Add("fileWirePath", files.EncodeWirePath("/docs/"+name))
		}
		response := h.request(t, owner.ID, rawHandler, http.MethodGet, "/docs?"+query.Encode(), nil, nil)
		if response.Code != http.StatusOK || !strings.Contains(response.Header().Get("Content-Type"), "fileway-selection=wire-v1") {
			t.Fatalf("ZIP export failed: %d %s", response.Code, response.Body.String())
		}
		reader, err := zip.NewReader(bytes.NewReader(response.Body.Bytes()), int64(response.Body.Len()))
		if err != nil {
			t.Fatal(err)
		}
		if len(reader.File) != len(names) {
			t.Fatalf("wrong ZIP selection: got %d entries want %d", len(reader.File), len(names))
		}
		seen := map[string]string{}
		for _, entry := range reader.File {
			file, err := entry.Open()
			if err != nil {
				t.Fatal(err)
			}
			content, err := io.ReadAll(file)
			_ = file.Close()
			if err != nil {
				t.Fatal(err)
			}
			seen[entry.Name] = string(content)
		}
		if seen["comma,name.txt"] != "comma-owned" {
			t.Fatalf("comma filename decoded/split incorrectly: %#v", seen)
		}
		if len(names) == 2 && seen["100% +?#.txt"] != "literal-owned" {
			t.Fatalf("literal filename decoded twice: %#v", seen)
		}
		if _, included := seen["not-selected.txt"]; included {
			t.Fatal("unselected parent member was exported")
		}
	}
}

func TestRawZIPWireSelectionRejectsOutsideMissingDeniedAndMalformedPaths(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}, Rules: []rules.Rule{{Path: "/docs/denied", Allow: false}}})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	if err := filesystem.MkdirAll("/docs", 0o750); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"/docs/owned", "/docs/denied", "/outside", "/docs/\xff"} {
		if err := afero.WriteFile(filesystem, name, []byte("owned"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, test := range []struct {
		wire   string
		status int
	}{{"/outside", 400}, {"/docs/missing", 404}, {"/docs/denied", 403}, {"/docs/bad%", 400}, {"/docs/a%2Fb", 400}, {"/docs/bad%00", 400}} {
		query := url.Values{"algo": {"zip"}, "fileWirePath": {test.wire}}
		response := h.request(t, owner.ID, rawHandler, http.MethodGet, "/docs?"+query.Encode(), nil, nil)
		if response.Code != test.status {
			t.Fatalf("wire=%q status=%d body=%s", test.wire, response.Code, response.Body.String())
		}
		if bytes.Contains(response.Body.Bytes(), []byte{'P', 'K', 5, 6}) {
			t.Fatal("invalid selection produced a completed ZIP")
		}
	}
	query := url.Values{"fileWirePath": {"/docs/%FF"}, "algo": {"zip"}}
	response := h.request(t, owner.ID, rawHandler, http.MethodGet, "/docs?"+query.Encode(), nil, nil)
	if runtime.GOOS == "windows" {
		if response.Code != http.StatusForbidden {
			t.Fatalf("Windows accepted invalid UTF-8 sibling path: %d", response.Code)
		}
	} else {
		if response.Code != http.StatusOK {
			t.Fatalf("Linux opaque ZIP failed: %d %s", response.Code, response.Body.String())
		}
		reader, err := zip.NewReader(bytes.NewReader(response.Body.Bytes()), int64(response.Body.Len()))
		if err != nil || len(reader.File) != 1 || reader.File[0].Name != "\xff" {
			t.Fatalf("opaque name lost: %#v %v", reader, err)
		}
	}
}

func TestZIPSourceFailureCannotEmitValidEndRecord(t *testing.T) {
	file, err := (fstest.MapFS{"file": &fstest.MapFile{Data: []byte("short")}}).Open("file")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	failure := &zipStreamFailure{}
	reader := &zipSourceReader{File: file, expected: 20, failure: failure}
	if _, err := io.ReadAll(reader); err != io.ErrUnexpectedEOF {
		t.Fatalf("truncated source accepted: %v", err)
	}
	var output bytes.Buffer
	if n, err := (zipGuardWriter{writer: &output, failure: failure}).Write([]byte{'P', 'K', 5, 6}); err == nil || n != 0 || output.Len() != 0 {
		t.Fatal("source failure was followed by a valid-looking EOCD")
	}
}
