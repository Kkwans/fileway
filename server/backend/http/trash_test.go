package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/asdine/storm/v3"
	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/mux"
	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/settings"
	appstorage "github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/storage/bolt"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

var trashHTTPKey = []byte("trash-http-test-key")

type trashHTTPHarness struct {
	storage *appstorage.Storage
	server  *settings.Server
	users   map[uint]*users.User
	fs      map[uint]afero.Fs
}

func newTrashHTTPHarness(t *testing.T, definitions ...users.User) *trashHTTPHarness {
	t.Helper()
	db, err := storm.Open(filepath.Join(t.TempDir(), "http-trash.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})
	persistent, err := bolt.NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	if err := persistent.Settings.Save(&settings.Settings{Key: trashHTTPKey}); err != nil {
		t.Fatal(err)
	}

	base := afero.NewMemMapFs()
	userMap := make(map[uint]*users.User, len(definitions))
	fsMap := make(map[uint]afero.Fs, len(definitions))
	for index := range definitions {
		definition := definitions[index]
		if definition.Password == "" {
			definition.Password = "secret123"
		}
		if definition.Scope == "" {
			definition.Scope = "/" + definition.Username
		}
		if err := persistent.Users.Save(&definition); err != nil {
			t.Fatal(err)
		}
		if err := base.MkdirAll(definition.Scope, 0o750); err != nil {
			t.Fatal(err)
		}
		userMap[definition.ID] = &definition
		fsMap[definition.ID] = afero.NewBasePathFs(base, definition.Scope)
	}
	persistent.Users = &userFilesystemStore{Store: persistent.Users, filesystems: fsMap}
	return &trashHTTPHarness{
		storage: persistent,
		server:  &settings.Server{},
		users:   userMap,
		fs:      fsMap,
	}
}

func (h *trashHTTPHarness) request(t *testing.T, userID uint, handler handleFunc, method, target string, body io.Reader, vars map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(method, target, body)
	request.Header.Set("X-Auth", signedTrashHTTPToken(t, userID))
	if vars != nil {
		request = mux.SetURLVars(request, vars)
	}
	recorder := httptest.NewRecorder()
	httpHandler := handle(handler, "", h.storage, h.server)
	httpHandler.ServeHTTP(recorder, request)
	return recorder
}

func TestTrashHTTPFlowAndLegacyPermanentDelete(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Perm: users.Permissions{
			Create: true, Delete: true, Modify: true, Rename: true, Download: true,
		},
	})
	owner := firstTrashHTTPUser(h)
	filesystem := h.fs[owner.ID]
	if err := afero.WriteFile(filesystem, "/recycle.txt", []byte("recycle"), 0o640); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/recycle.txt?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("trash delete status = %d body=%s", response.Code, response.Body.String())
	}
	var moved trash.PublicItem
	if err := json.Unmarshal(response.Body.Bytes(), &moved); err != nil {
		t.Fatal(err)
	}
	if moved.ID == "" || moved.OriginalPath != "/recycle.txt" {
		t.Fatalf("trash delete response = %#v", moved)
	}
	if exists, _ := afero.Exists(filesystem, "/recycle.txt"); exists {
		t.Fatal("resource still exists after trash delete")
	}

	response = h.request(t, owner.ID, trashListHandler, http.MethodGet, "/trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("trash list status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "storedPath") || strings.Contains(response.Body.String(), "snapshots") {
		t.Fatalf("trash list leaked internal state: %s", response.Body.String())
	}
	var items []trash.PublicItem
	if err := json.Unmarshal(response.Body.Bytes(), &items); err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].OriginalPath != "/recycle.txt" || items[0].UserID != owner.ID {
		t.Fatalf("trash items = %#v", items)
	}

	response = h.request(t, owner.ID, trashRestoreHandler, http.MethodPost, "/trash/"+items[0].ID+"/restore", bytes.NewBufferString(`{"conflict":"fail"}`), map[string]string{"id": items[0].ID})
	if response.Code != http.StatusOK {
		t.Fatalf("restore status = %d body=%s", response.Code, response.Body.String())
	}
	content, err := afero.ReadFile(filesystem, "/recycle.txt")
	if err != nil || string(content) != "recycle" {
		t.Fatalf("restored content = %q err=%v", content, err)
	}

	if err := afero.WriteFile(filesystem, "/legacy.txt", []byte("legacy"), 0o640); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/legacy.txt", nil, nil)
	if response.Code != http.StatusNoContent {
		t.Fatalf("legacy delete status = %d body=%s", response.Code, response.Body.String())
	}
	if exists, _ := afero.Exists(filesystem, "/legacy.txt"); exists {
		t.Fatal("legacy delete must remain permanent")
	}
	remaining, err := h.storage.Trash.List(owner.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(remaining) != 0 {
		t.Fatalf("legacy delete unexpectedly created trash items: %#v", remaining)
	}

	if err := afero.WriteFile(filesystem, "/unknown.txt", []byte("unknown"), 0o640); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/unknown.txt?mode=unknown", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unknown mode status = %d body=%s", response.Code, response.Body.String())
	}
	if exists, _ := afero.Exists(filesystem, "/unknown.txt"); !exists {
		t.Fatal("unknown delete mode modified the resource")
	}
	contextData := &data{user: owner, settings: &settings.Settings{}}
	if contextData.Check("/.nas-file-browser-trash/owner/private") {
		t.Fatal("internal recycle-bin path must never pass normal resource checks")
	}
}

func TestAdminRestoresTrashIntoOwnersFilesystem(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true, Create: true, Delete: true}},
		users.User{Username: "member", Perm: users.Permissions{Create: true, Delete: true, Modify: true, Download: true}},
	)
	var admin, member *users.User
	for _, user := range h.users {
		if user.Perm.Admin {
			admin = user
		} else {
			member = user
		}
	}
	if admin == nil || member == nil {
		t.Fatal("test users were not created")
	}
	if err := afero.WriteFile(h.fs[member.ID], "/owned.txt", []byte("member"), 0o640); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, member.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, "/owned.txt?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member trash status = %d body=%s", response.Code, response.Body.String())
	}

	items, err := h.storage.Trash.List(admin.ID, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].UserID != member.ID {
		t.Fatalf("admin trash items = %#v", items)
	}
	response = h.request(t, admin.ID, trashRestoreHandler, http.MethodPost, "/trash/"+items[0].ID+"/restore", bytes.NewBufferString(`{"conflict":"fail"}`), map[string]string{"id": items[0].ID})
	if response.Code != http.StatusOK {
		t.Fatalf("admin restore status = %d body=%s", response.Code, response.Body.String())
	}
	content, err := afero.ReadFile(h.fs[member.ID], "/owned.txt")
	if err != nil || string(content) != "member" {
		t.Fatalf("member file = %q err=%v", content, err)
	}
	if exists, _ := afero.Exists(h.fs[admin.ID], "/owned.txt"); exists {
		t.Fatal("admin restore wrote into the administrator filesystem")
	}
}

func firstTrashHTTPUser(h *trashHTTPHarness) *users.User {
	for _, user := range h.users {
		return user
	}
	return nil
}

func signedTrashHTTPToken(t *testing.T, userID uint) string {
	t.Helper()
	now := time.Now()
	claims := authToken{
		User: userInfo{ID: userID},
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(time.Hour)),
		},
	}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(trashHTTPKey)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

type userFilesystemStore struct {
	users.Store
	filesystems map[uint]afero.Fs
}

func (store *userFilesystemStore) Get(baseScope string, id interface{}) (*users.User, error) {
	user, err := store.Store.Get(baseScope, id)
	if err != nil {
		return nil, err
	}
	if filesystem := store.filesystems[user.ID]; filesystem != nil {
		user.Fs = filesystem
	}
	return user, nil
}

type noopTrashFileCache struct{}

func (noopTrashFileCache) Store(context.Context, string, []byte) error { return nil }
func (noopTrashFileCache) Load(context.Context, string) ([]byte, bool, error) {
	return nil, false, nil
}
func (noopTrashFileCache) Delete(context.Context, string) error { return nil }
