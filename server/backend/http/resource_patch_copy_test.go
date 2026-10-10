package fbhttp

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

var errPatchCopyRead = errors.New("owned PATCH source read fault")

type patchCopyFaultFS struct {
	afero.Fs
	failRead   bool
	beforeRead func()
}

func (f *patchCopyFaultFS) LstatIfPossible(name string) (os.FileInfo, bool, error) {
	return f.Fs.(afero.Lstater).LstatIfPossible(name)
}
func (f *patchCopyFaultFS) Open(name string) (afero.File, error) {
	file, err := f.Fs.Open(name)
	if err != nil {
		return nil, err
	}
	if filepath.Base(name) == "source.txt" {
		return &patchCopySource{File: file, fs: f}, nil
	}
	return file, nil
}

type patchCopySource struct {
	afero.File
	fs *patchCopyFaultFS
}

func (f *patchCopySource) Read(p []byte) (int, error) {
	if f.fs.beforeRead != nil {
		action := f.fs.beforeRead
		f.fs.beforeRead = nil
		action()
	}
	if f.fs.failRead {
		n, _ := f.File.Read(p[:min(3, len(p))])
		return n, errPatchCopyRead
	}
	return f.File.Read(p)
}
func patchCopyFixture(t *testing.T) (*trashHTTPHarness, *users.User, afero.Fs, *patchCopyFaultFS) {
	t.Helper()
	root := t.TempDir()
	h := newTrashHTTPHarness(t, users.User{Username: "copy-owner", Scope: root, Perm: users.Permissions{Create: true, Modify: true, Download: true}})
	owner := firstTrashHTTPUser(h)
	fault := &patchCopyFaultFS{Fs: afero.NewOsFs()}
	filesystem := afero.NewBasePathFs(fault, root)
	h.fs[owner.ID] = filesystem
	if err := afero.WriteFile(filesystem, "/source.txt", []byte("new-source"), 0o640); err != nil {
		t.Fatal(err)
	}
	return h, owner, filesystem, fault
}
func patchCopyRequest(t *testing.T, h *trashHTTPHarness, owner *users.User, override bool) *httptest.ResponseRecorder {
	t.Helper()
	query := url.Values{"action": {"copy"}, "destinationWirePath": {"/target.txt"}}
	if override {
		query.Set("override", "true")
	}
	return h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source.txt?"+query.Encode(), nil, nil)
}
func TestLegacyPatchCopyFailurePreservesOldDestination(t *testing.T) {
	h, owner, filesystem, fault := patchCopyFixture(t)
	if err := afero.WriteFile(filesystem, "/target.txt", []byte("old-target"), 0o640); err != nil {
		t.Fatal(err)
	}
	fault.failRead = true
	response := patchCopyRequest(t, h, owner, true)
	fault.failRead = false
	if response.Code != 500 {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"})
}
func TestLegacyPatchCopyConcurrentDestinationIsNotOverwritten(t *testing.T) {
	h, owner, filesystem, fault := patchCopyFixture(t)
	fault.beforeRead = func() {
		if err := afero.WriteFile(filesystem, "/target.txt", []byte("other-writer"), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	response := patchCopyRequest(t, h, owner, false)
	if response.Code != http.StatusConflict {
		t.Errorf("status=%d body=%s", response.Code, response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", "/target.txt": "other-writer"})
	if response.Header().Get("X-Resource-Destination-WirePath") != "" {
		t.Fatal("failed copy acknowledged a destination")
	}
}

func TestLegacyPatchCopyApprovedOverwriteAndMerge(t *testing.T) {
	h, owner, filesystem, _ := patchCopyFixture(t)
	if err := afero.WriteFile(filesystem, "/target.txt", []byte("old-target"), 0o640); err != nil {
		t.Fatal(err)
	}
	response := patchCopyRequest(t, h, owner, false)
	if response.Code != 409 {
		t.Fatalf("no-overwrite status=%d", response.Code)
	}
	assertWireOwned(t, filesystem, map[string]string{"/target.txt": "old-target"})
	response = patchCopyRequest(t, h, owner, true)
	if response.Code != 200 {
		t.Fatalf("approved status=%d body=%s", response.Code, response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", "/target.txt": "new-source"})
	if response.Header().Get("X-Resource-Destination-WirePath") != "/target.txt" {
		t.Fatal("missing actual destination ACK")
	}
	for name, body := range map[string]string{"/source/child": "new-child", "/target/child": "old-child", "/target/other": "keep"} {
		if err := filesystem.MkdirAll(filepath.Dir(name), 0o750); err != nil {
			t.Fatal(err)
		}
		if err := afero.WriteFile(filesystem, name, []byte(body), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source?action=copy&destinationWirePath=%2Ftarget&override=true", nil, nil)
	if response.Code != 200 {
		t.Fatalf("merge status=%d body=%s", response.Code, response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{"/target/child": "new-child", "/target/other": "keep", "/source/child": "new-child"})
}

func TestLegacyPatchCopyKeepBothRejectsLateSuffixCollision(t *testing.T) {
	h, owner, filesystem, fault := patchCopyFixture(t)
	if err := afero.WriteFile(filesystem, "/target.txt", []byte("old-target"), 0o640); err != nil {
		t.Fatal(err)
	}
	fault.beforeRead = func() {
		if err := afero.WriteFile(filesystem, "/target(1).txt", []byte("other-writer"), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	response := h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source.txt?action=copy&destinationWirePath=%2Ftarget.txt&rename=true&override=true", nil, nil)
	if response.Code != 409 {
		t.Fatalf("suffix collision status=%d body=%s", response.Code, response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", "/target.txt": "old-target", "/target(1).txt": "other-writer"})
}

func TestLegacyPatchCopyRequiresCreateAndApprovedModify(t *testing.T) {
	for _, permission := range []string{"create", "modify"} {
		t.Run(permission, func(t *testing.T) {
			h, owner, filesystem, _ := patchCopyFixture(t)
			if err := afero.WriteFile(filesystem, "/target.txt", []byte("old-target"), 0o640); err != nil {
				t.Fatal(err)
			}
			updateTransferActor(t, h, owner.ID, func(actor *users.User) {
				if permission == "create" {
					actor.Perm.Create = false
				} else {
					actor.Perm.Modify = false
				}
			})
			response := patchCopyRequest(t, h, owner, true)
			if response.Code != 403 {
				t.Fatalf("permission status=%d body=%s", response.Code, response.Body.String())
			}
			assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", "/target.txt": "old-target"})
		})
	}
}

func TestLegacyPatchCopySameFileIsBadRequestButKeepBothWorks(t *testing.T) {
	for _, alias := range []bool{false, true} {
		for _, keepBoth := range []bool{false, true} {
			for _, override := range []bool{false, true} {
				t.Run(fmt.Sprintf("alias=%t/keepBoth=%t/override=%t", alias, keepBoth, override), func(t *testing.T) {
					h, owner, filesystem, _ := patchCopyFixture(t)
					target := "/source.txt"
					if alias {
						target = "/alias.txt"
						if err := os.Link(filepath.Join(owner.Scope, "source.txt"), filepath.Join(owner.Scope, "alias.txt")); err != nil {
							t.Fatal(err)
						}
					}
					query := url.Values{"action": {"copy"}, "destinationWirePath": {target}}
					if override {
						query.Set("override", "true")
					}
					if keepBoth {
						query.Set("rename", "true")
					}
					response := h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source.txt?"+query.Encode(), nil, nil)
					want := http.StatusBadRequest
					if keepBoth {
						want = http.StatusOK
					}
					if response.Code != want {
						t.Fatalf("status=%d want=%d body=%s", response.Code, want, response.Body.String())
					}
					assertWireOwned(t, filesystem, map[string]string{"/source.txt": "new-source", target: "new-source"})
					if keepBoth {
						copied := strings.TrimSuffix(target, ".txt") + "(1).txt"
						assertWireOwned(t, filesystem, map[string]string{copied: "new-source"})
						if response.Header().Get("X-Resource-Destination-WirePath") != files.EncodeWirePath(copied) {
							t.Fatalf("destination ACK=%q", response.Header().Get("X-Resource-Destination-WirePath"))
						}
					} else if response.Header().Get("X-Resource-Destination-WirePath") != "" {
						t.Fatal("bad request acknowledged a write")
					}
				})
			}
		}
	}
}
