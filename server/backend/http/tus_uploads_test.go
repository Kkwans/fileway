package fbhttp

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func nativeTusHarness(t *testing.T) *trashHTTPHarness {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner", Perm: users.Permissions{Create: true, Modify: true, Delete: true, Download: true}},
		users.User{ID: 2, Username: "other", Perm: users.Permissions{Create: true, Modify: true, Delete: true, Download: true}})
	root := t.TempDir()
	for id := range h.fs {
		folder := filepath.Join(root, strconv.Itoa(int(id)))
		if err := os.MkdirAll(folder, 0700); err != nil {
			t.Fatal(err)
		}
		h.fs[id] = afero.NewBasePathFs(afero.NewOsFs(), folder)
	}
	return h
}

func TestTusTargetKeyStableWhenCreatingFoldersBelowSymlink(t *testing.T) {
	root := t.TempDir()
	real := filepath.Join(root, "real")
	if err := os.Mkdir(real, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(real, filepath.Join(root, "alias")); err != nil {
		t.Skipf("native symlinks unavailable: %v", err)
	}
	d := &data{user: &users.User{Fs: afero.NewBasePathFs(afero.NewOsFs(), root)}}
	before, err := tusTargetKey(d, "/alias/new/nested/owned.bin")
	if err != nil {
		t.Fatal(err)
	}
	if err := d.user.Fs.MkdirAll("/alias/new/nested", 0700); err != nil {
		t.Fatal(err)
	}
	after, err := tusTargetKey(d, "/alias/new/nested/owned.bin")
	if err != nil || before != after {
		t.Fatalf("target key changed after folder creation: before=%q after=%q err=%v", before, after, err)
	}
}
func tusRequest(t *testing.T, h *trashHTTPHarness, user uint, method, target, id string, length, offset int64, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, target, bytes.NewReader(body))
	r.Header.Set("X-Auth", signedTrashHTTPToken(t, user))
	r.Header.Set("X-Transfer-ID", id)
	r.Header.Set("Tus-Resumable", "1.0.0")
	var fn handleFunc
	switch method {
	case "POST":
		fn = tusPostHandler(nil)
		r.Header.Set("Upload-Length", strconv.FormatInt(length, 10))
	case "HEAD":
		fn = tusHeadHandler(nil)
	case "PATCH":
		fn = tusPatchHandler(nil)
		r.Header.Set("Content-Type", "application/offset+octet-stream")
		r.Header.Set("Upload-Offset", strconv.FormatInt(offset, 10))
	case "DELETE":
		fn = tusDeleteHandler(nil)
	}
	w := httptest.NewRecorder()
	handle(fn, "", h.storage, h.server).ServeHTTP(w, r)
	return w
}
func expectTus(t *testing.T, r *httptest.ResponseRecorder, status int) {
	t.Helper()
	if r.Code != status {
		t.Fatalf("status=%d want=%d body=%s", r.Code, status, r.Body.String())
	}
}
func ownedTusRow(t *testing.T, h *trashHTTPHarness, target, id string) *uploads.Session {
	t.Helper()
	d := &data{user: &users.User{ID: 1, Fs: h.fs[1]}}
	key, err := tusTargetKey(d, target)
	if err != nil {
		t.Fatal(err)
	}
	row, err := h.storage.Uploads.Get(id, 1, (&url.URL{Path: target}).EscapedPath(), key)
	if err != nil {
		t.Fatal(err)
	}
	return row
}

func TestTusPrivatePartsRetainPauseAndBindAccountTargetLength(t *testing.T) {
	h := nativeTusHarness(t)
	target := "/目录 +% #/owned.bin"
	wire := (&url.URL{Path: target}).EscapedPath()
	id := "owned-session"
	create := tusRequest(t, h, 1, "POST", wire+"?override=false", id, 8, 0, nil)
	expectTus(t, create, 201)
	if create.Header().Get("Tus-Resumable") != "1.0.0" || create.Header().Get("Upload-Expires") == "" {
		t.Fatal(create.Header())
	}
	if _, err := h.fs[1].Stat(target); !os.IsNotExist(err) {
		t.Fatal("incomplete target was published", err)
	}
	expectTus(t, tusRequest(t, h, 1, "PATCH", wire, id, 8, 0, []byte("abcd")), 204)
	row := ownedTusRow(t, h, target, id)
	part := mustUploadPath(row.PartWire)
	value, _ := afero.ReadFile(h.fs[1], part)
	if string(value) != "abcd" {
		t.Fatal(string(value))
	}
	if row.ExpiresAt-time.Now().UnixMilli() < int64((uploads.Retention-time.Minute)/time.Millisecond) {
		t.Fatal("pause retention shorter than7days")
	}
	expectTus(t, tusRequest(t, h, 1, "POST", wire, id, 8, 0, nil), 201)
	head := tusRequest(t, h, 1, "HEAD", wire, id, 8, 0, nil)
	expectTus(t, head, 200)
	if head.Header().Get("Upload-Offset") != "4" {
		t.Fatal(head.Header())
	}
	expectTus(t, tusRequest(t, h, 1, "POST", wire, id, 9, 0, nil), 409)
	expectTus(t, tusRequest(t, h, 1, "HEAD", "/elsewhere.bin", id, 8, 0, nil), 409)
	h.fs[2] = h.fs[1] // another account with access to the same physical filesystem
	expectTus(t, tusRequest(t, h, 2, "HEAD", wire, id, 8, 0, nil), 404)
	expectTus(t, tusRequest(t, h, 2, "DELETE", wire, id, 8, 0, nil), 404)
	expectTus(t, tusRequest(t, h, 1, "PATCH", wire, id, 8, 4, []byte("efgh")), 204)
	value, _ = afero.ReadFile(h.fs[1], target)
	if string(value) != "abcdefgh" {
		t.Fatal(string(value))
	}
	head = tusRequest(t, h, 1, "HEAD", wire, id, 8, 0, nil)
	expectTus(t, head, 200)
	if head.Header().Get("Upload-Offset") != "8" {
		t.Fatal(head.Header())
	}
	expectTus(t, tusRequest(t, h, 1, "DELETE", wire, id, 8, 0, nil), 409)
	value, _ = afero.ReadFile(h.fs[1], target)
	if string(value) != "abcdefgh" {
		t.Fatal("complete file removed")
	}
	resource := h.request(t, 1, resourceGetHandler, http.MethodGet, row.PartWire, nil, nil)
	if resource.Code < 400 {
		t.Fatal("private part accessible as ordinary resource")
	}
}

func TestTusCrashIntentReconcilesActualPartAndExpiresOnlyOwnedPart(t *testing.T) {
	h := nativeTusHarness(t)
	target := "/owned.bin"
	id := "owned-crash"
	expectTus(t, tusRequest(t, h, 1, "POST", target, id, 8, 0, nil), 201)
	expectTus(t, tusRequest(t, h, 1, "PATCH", target, id, 8, 0, []byte("abcd")), 204)
	row := ownedTusRow(t, h, target, id)
	row.Writing = true
	row.ReservedTo = 8
	if err := h.storage.Uploads.Save(row); err != nil {
		t.Fatal(err)
	}
	part := mustUploadPath(row.PartWire)
	f, err := h.fs[1].OpenFile(part, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		t.Fatal(err)
	}
	f.Write([]byte("ef"))
	f.Sync()
	f.Close()
	head := tusRequest(t, h, 1, "HEAD", target, id, 8, 0, nil)
	expectTus(t, head, 200)
	if head.Header().Get("Upload-Offset") != "6" {
		t.Fatal("crash bytes lost", head.Header())
	}
	expectTus(t, tusRequest(t, h, 1, "PATCH", target, id, 8, 6, []byte("gh")), 204)
	value, _ := afero.ReadFile(h.fs[1], target)
	if string(value) != "abcdefgh" {
		t.Fatal(string(value))
	}
	for _, replace := range []bool{false, true} {
		name := "/expire-" + strconv.FormatBool(replace) + ".bin"
		session := "expire-" + strconv.FormatBool(replace)
		expectTus(t, tusRequest(t, h, 1, "POST", name, session, 8, 0, nil), 201)
		expectTus(t, tusRequest(t, h, 1, "PATCH", name, session, 8, 0, []byte("abcd")), 204)
		row = ownedTusRow(t, h, name, session)
		part = mustUploadPath(row.PartWire)
		if replace {
			h.fs[1].Remove(part)
			if err := afero.WriteFile(h.fs[1], part, []byte("replacement normal file"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		row.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
		if err := h.storage.Uploads.Save(row); err != nil {
			t.Fatal(err)
		}
		expectTus(t, tusRequest(t, h, 1, "HEAD", name, session, 8, 0, nil), 410)
		value, err := afero.ReadFile(h.fs[1], part)
		if replace && string(value) != "replacement normal file" {
			t.Fatal("replacement removed", err)
		}
		if !replace && !os.IsNotExist(err) {
			t.Fatal("owned expired part retained", err)
		}
	}
}

func TestTusBoundsConcurrentRequestsAndFinalTargetConflict(t *testing.T) {
	h := nativeTusHarness(t)
	expectTus(t, tusRequest(t, h, 1, "POST", "/owned.bin", "owned", 8, 0, nil), 201)
	expectTus(t, tusRequest(t, h, 1, "PATCH", "/owned.bin", "owned", 8, 0, []byte("123456789")), 413)
	expectTus(t, tusRequest(t, h, 1, "PATCH", "/owned.bin", "owned", 8, -1, []byte("bad")), 400)
	row := ownedTusRow(t, h, "/owned.bin", "owned")
	if row.Offset != 0 {
		t.Fatal(row.Offset)
	}
	var group sync.WaitGroup
	statuses := make(chan int, 2)
	for _, body := range []string{"abcd", "ABCD"} {
		group.Add(1)
		go func(text string) {
			defer group.Done()
			statuses <- tusRequest(t, h, 1, "PATCH", "/owned.bin", "owned", 8, 0, []byte(text)).Code
		}(body)
	}
	group.Wait()
	close(statuses)
	ok, conflict := 0, 0
	for status := range statuses {
		if status == 204 {
			ok++
		} else if status == 409 {
			conflict++
		} else {
			t.Fatal(status)
		}
	}
	if ok != 1 || conflict != 1 {
		t.Fatal(ok, conflict)
	}
	if err := afero.WriteFile(h.fs[1], "/owned.bin", []byte("unrelated original"), 0600); err != nil {
		t.Fatal(err)
	}
	expectTus(t, tusRequest(t, h, 1, "PATCH", "/owned.bin", "owned", 8, 4, []byte("efgh")), 409)
	value, _ := afero.ReadFile(h.fs[1], "/owned.bin")
	if string(value) != "unrelated original" {
		t.Fatal("final target silently overwritten")
	}
	row = ownedTusRow(t, h, "/owned.bin", "owned")
	if row.Offset != 8 || row.State != uploads.Active {
		t.Fatal("received part lost on publication conflict", row)
	}
}

func TestTusConcurrentCreationReservesOnePhysicalTarget(t *testing.T) {
	h := nativeTusHarness(t)
	var group sync.WaitGroup
	codes := make(chan int, 2)
	for _, id := range []string{"owned-one", "owned-two"} {
		group.Add(1)
		go func(session string) {
			defer group.Done()
			codes <- tusRequest(t, h, 1, "POST", "/same.bin", session, 8, 0, nil).Code
		}(id)
	}
	group.Wait()
	close(codes)
	created, conflicts := 0, 0
	for code := range codes {
		if code == 201 {
			created++
		} else if code == 409 {
			conflicts++
		} else {
			t.Fatal(code)
		}
	}
	if created != 1 || conflicts != 1 {
		t.Fatal(created, conflicts)
	}
	if _, err := h.fs[1].Stat("/same.bin"); !os.IsNotExist(err) {
		t.Fatal("creation published empty target", err)
	}
}

func TestTusApprovedOverwriteZeroFileAndHiddenPartListing(t *testing.T) {
	h := nativeTusHarness(t)
	if err := afero.WriteFile(h.fs[1], "/original.bin", []byte("original"), 0600); err != nil {
		t.Fatal(err)
	}
	expectTus(t, tusRequest(t, h, 1, "POST", "/original.bin?override=true", "overwrite", 8, 0, nil), 201)
	expectTus(t, tusRequest(t, h, 1, "PATCH", "/original.bin", "overwrite", 8, 0, []byte("abcd")), 204)
	value, _ := afero.ReadFile(h.fs[1], "/original.bin")
	if string(value) != "original" {
		t.Fatal("original truncated while incomplete")
	}
	expectTus(t, tusRequest(t, h, 1, "PATCH", "/original.bin", "overwrite", 8, 4, []byte("efgh")), 204)
	value, _ = afero.ReadFile(h.fs[1], "/original.bin")
	if string(value) != "abcdefgh" {
		t.Fatal(string(value))
	}
	expectTus(t, tusRequest(t, h, 1, "POST", "/zero.bin", "zero", 0, 0, nil), 201)
	expectTus(t, tusRequest(t, h, 1, "HEAD", "/zero.bin", "zero", 0, 0, nil), 200)
	if info, err := h.fs[1].Stat("/zero.bin"); err != nil || info.Size() != 0 {
		t.Fatal(info, err)
	}
	expectTus(t, tusRequest(t, h, 1, "POST", "/pending.bin", "pending", 8, 0, nil), 201)
	listing := h.request(t, 1, resourceGetHandler, "GET", "/", nil, nil)
	if listing.Code != 200 || strings.Contains(listing.Body.String(), files.UploadPartPrefix) {
		t.Fatal("private upload part shown in file listing", listing.Body.String())
	}
}
