package fbhttp

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/files"
	boltstore "github.com/Kkwans/nas-file-browser/backend/storage/bolt"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/asdine/storm/v3"
	"github.com/spf13/afero"
)

func TestArchiveWirePreservesFilesystemAndSameDisplayEntryIdentities(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true, Create: true}})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	source := "/ \xd6\xd0\xce\xc4\\bundle.zip "
	destination := "/ \xff output "
	opaque, sibling := "\xd6\xd0\xce\xc4.txt", "中文.txt"
	var data bytes.Buffer
	writer := zip.NewWriter(&data)
	for name, content := range map[string]string{opaque: "opaque-owned", sibling: "unicode-sibling", "a%2F +?#.txt": "literal"} {
		entry, err := writer.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Store, NonUTF8: true})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, source, data.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := filesystem.MkdirAll(destination, 0o750); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, archiveEntriesHandler, http.MethodGet, "/archives/entries?"+url.Values{"wirePath": {files.EncodeWirePath(source)}}.Encode(), nil, nil)
	if runtime.GOOS == "windows" {
		if response.Code != http.StatusBadRequest {
			t.Fatalf("Windows accepted unsupported raw path: %d %s", response.Code, response.Body.String())
		}
		listing, err := afero.ReadDir(filesystem, destination)
		if err != nil || len(listing) != 0 {
			t.Fatalf("rejected path changed destination: %#v %v", listing, err)
		}
		return
	}
	if response.Code != http.StatusOK {
		t.Fatalf("raw listing status=%d body=%s", response.Code, response.Body.String())
	}
	var listing archivefs.Listing
	if err := json.Unmarshal(response.Body.Bytes(), &listing); err != nil {
		t.Fatal(err)
	}
	if listing.ArchivePath != source || len(listing.Entries) != 3 {
		t.Fatalf("listing lost source or entries: %#v", listing)
	}
	seen := map[string]bool{}
	for _, entry := range listing.Entries {
		seen[entry.Path] = true
	}
	if !seen[opaque] || !seen[sibling] {
		t.Fatalf("same-display byte siblings merged: %#v", seen)
	}
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(archiveExtractRequest{ArchiveWirePath: files.EncodeWirePath(source), DestinationWirePath: files.EncodeWirePath(destination), SelectedWirePaths: []string{files.EncodeWirePath(opaque)}})
	response = h.request(t, owner.ID, archiveExtractStartHandler(runner), http.MethodPost, "/archives/extractions", bytes.NewReader(body), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("raw extraction status=%d body=%s", response.Code, response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	var args archiveExtractTaskArgs
	if err := json.Unmarshal(completed.Args, &args); err != nil {
		t.Fatal(err)
	}
	if args.ArchivePath != source || args.Destination != destination || len(args.Selected) != 1 || args.Selected[0] != opaque {
		t.Fatalf("persisted args lost bytes: %#v", args)
	}
	content, err := afero.ReadFile(filesystem, destination+"/"+opaque)
	if err != nil || string(content) != "opaque-owned" {
		t.Fatalf("wrong selected entry extracted: %q %v", content, err)
	}
	if exists, _ := afero.Exists(filesystem, destination+"/"+sibling); exists {
		t.Fatal("same-display Unicode sibling was extracted")
	}
	var report archivefs.ExtractReport
	if err := json.Unmarshal(completed.Result, &report); err != nil {
		t.Fatal(err)
	}
	if report.ArchivePath != source || report.Destination != destination || report.Selected[0] != opaque || report.PathsUnverified {
		t.Fatalf("result lost raw identities: %#v", report)
	}
}

func TestArchiveWireRejectsConflictsAndZipSlipWithoutMutation(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true, Create: true}})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	writeTarFixture(t, filesystem, "/bundle.tar", map[string]string{"file.txt": "owned"})
	if err := filesystem.MkdirAll("/output", 0o750); err != nil {
		t.Fatal(err)
	}
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	for _, request := range []archiveExtractRequest{
		{ArchivePath: "/other.tar", ArchiveWirePath: "/bundle.tar", Destination: "/output", Selected: []string{"file.txt"}},
		{ArchiveWirePath: "/bundle.tar", Destination: "/wrong", DestinationWirePath: "/output", Selected: []string{"file.txt"}},
		{ArchiveWirePath: "/bad%", Destination: "/output", Selected: []string{"file.txt"}},
		{ArchiveWirePath: "/bundle.tar", DestinationWirePath: "/output%00", Selected: []string{"file.txt"}},
		{ArchiveWirePath: "/bundle.tar", Destination: "/output", SelectedWirePaths: []string{"%2E%2E/escape"}},
		{ArchiveWirePath: "/bundle.tar", Destination: "/output", SelectedWirePaths: []string{"a%2Fb"}},
		{ArchiveWirePath: "/bundle.tar", Destination: "/output", Selected: []string{"different"}, SelectedWirePaths: []string{"file.txt"}},
	} {
		body, _ := json.Marshal(request)
		response := h.request(t, owner.ID, archiveExtractStartHandler(runner), http.MethodPost, "/archives/extractions", bytes.NewReader(body), nil)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("invalid request accepted: %#v status=%d body=%s", request, response.Code, response.Body.String())
		}
		listing, err := afero.ReadDir(filesystem, "/output")
		if err != nil || len(listing) != 0 {
			t.Fatalf("invalid request wrote files: %#v %v", listing, err)
		}
	}
}

func TestArchiveTaskWireArgsSurviveBoltCloseAndReopen(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows intentionally rejects non-UTF-8 filesystem identities")
	}
	databasePath := filepath.Join(t.TempDir(), "archive-tasks.db")
	db, err := storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	storage, err := boltstore.NewStorage(db)
	if err != nil {
		_ = db.Close()
		t.Fatal(err)
	}
	expected := archiveExtractTaskArgs{ArchivePath: "/\xff.zip", Destination: "/ \xfe output ", Selected: []string{"\xfd +%?#"}, SourceSize: 123, SourceModified: 456}
	args, err := json.Marshal(expected)
	if err != nil {
		_ = db.Close()
		t.Fatal(err)
	}
	task, err := storage.Tasks.New(7, "owned", tasks.TypeArchiveExtract, "owned archive task", args, "")
	if err != nil {
		_ = db.Close()
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	}()
	storage, err = boltstore.NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := storage.Tasks.Get(7, task.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	var restored archiveExtractTaskArgs
	if err := json.Unmarshal(loaded.Args, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.ArchivePath != expected.ArchivePath || restored.Destination != expected.Destination || restored.Selected[0] != expected.Selected[0] || restored.SourceSize != 123 || restored.SourceModified != 456 {
		t.Fatalf("reopened task lost raw identity: %#v", restored)
	}
	if err := json.Unmarshal([]byte(`{"archivePath":"/lost�.zip","destination":"/output","selected":["file.txt"]}`), &restored); err == nil {
		t.Fatal("legacy damaged task was certified as a Unicode sibling")
	}
}
