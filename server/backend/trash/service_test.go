package trash_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/asdine/storm/v3"
	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	appstorage "github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/storage/bolt"
	"github.com/Kkwans/nas-file-browser/backend/trash"
)

const (
	testUserID   = uint(7)
	testUserName = "owner"
	testPath     = "/docs/file.txt"
)

type serviceHarness struct {
	fs      afero.Fs
	storage *appstorage.Storage
	service *trash.Service
}

func newServiceHarness(t *testing.T) *serviceHarness {
	t.Helper()
	root := filepath.Join(t.TempDir(), "filesystem")
	if err := os.MkdirAll(root, 0o750); err != nil {
		t.Fatal(err)
	}
	db, err := storm.Open(filepath.Join(t.TempDir(), "state.db"))
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
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	return &serviceHarness{
		fs:      filesystem,
		storage: persistent,
		service: &trash.Service{
			Fs: filesystem, Records: persistent.Trash,
			Favorites: persistent.Favorites, Tags: persistent.Tags, Recent: persistent.Recent,
		},
	}
}

func (h *serviceHarness) writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := h.fs.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs, path, []byte(content), 0o640); err != nil {
		t.Fatal(err)
	}
}

func (h *serviceHarness) addMetadata(t *testing.T, path string) (*favorites.Favorite, string) {
	t.Helper()
	favorite, err := h.storage.Favorites.Add(testUserID, path, filepath.Base(path), 0)
	if err != nil {
		t.Fatal(err)
	}
	tag, err := h.storage.Tags.Create(testUserID, "工作", "#1677ff")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.storage.Tags.AddPath(testUserID, tag.ID, path); err != nil {
		t.Fatal(err)
	}
	return favorite, tag.ID
}

func TestServiceMoveAndRestorePreservesOwnedMetadata(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, testPath, "original")
	favorite, tagID := h.addMetadata(t, testPath)

	item, err := h.service.Move(testUserID, testUserName, testPath)
	if err != nil {
		t.Fatal(err)
	}
	if exists, _ := afero.Exists(h.fs, testPath); exists {
		t.Fatal("source still exists after move")
	}
	if exists, _ := afero.Exists(h.fs, item.StoredPath); !exists {
		t.Fatal("stored file does not exist")
	}
	if _, err := h.storage.Favorites.GetByID(testUserID, favorite.ID); !errors.Is(err, favorites.ErrNotExist) {
		t.Fatalf("favorite was not staged: %v", err)
	}
	tag, err := h.storage.Tags.GetByID(testUserID, tagID)
	if err != nil {
		t.Fatal(err)
	}
	if len(tag.Paths) != 0 {
		t.Fatalf("tag paths were not staged: %#v", tag.Paths)
	}
	if len(item.FavoriteSnapshots) != 1 || item.FavoriteSnapshots[0].UserID != testUserID {
		t.Fatalf("favorite snapshot = %#v", item.FavoriteSnapshots)
	}
	if len(item.TagSnapshots) != 1 || item.TagSnapshots[0].UserID != testUserID {
		t.Fatalf("tag snapshot = %#v", item.TagSnapshots)
	}

	result, err := h.service.Restore(testUserID, item.ID, false, trash.ConflictFail)
	if err != nil {
		t.Fatal(err)
	}
	if result.Path != testPath || result.Skipped {
		t.Fatalf("restore result = %#v", result)
	}
	assertFileContent(t, h.fs, testPath, "original")
	restoredFavorite, err := h.storage.Favorites.GetByID(testUserID, favorite.ID)
	if err != nil {
		t.Fatal(err)
	}
	if restoredFavorite.UserID != testUserID || restoredFavorite.Path != testPath {
		t.Fatalf("restored favorite = %#v", restoredFavorite)
	}
	restoredTag, err := h.storage.Tags.GetByID(testUserID, tagID)
	if err != nil {
		t.Fatal(err)
	}
	if !equalStrings(restoredTag.Paths, []string{testPath}) {
		t.Fatalf("restored tag paths = %#v", restoredTag.Paths)
	}
	if _, err := h.storage.Trash.Get(testUserID, item.ID, false); !errors.Is(err, trash.ErrNotExist) {
		t.Fatalf("trash record remains after restore: %v", err)
	}
}

func TestServiceKeepBothPreservesChangesMadeWhileTrashed(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, testPath, "original")
	favorite, tagID := h.addMetadata(t, testPath)
	item, err := h.service.Move(testUserID, testUserName, testPath)
	if err != nil {
		t.Fatal(err)
	}
	h.writeFile(t, testPath, "replacement")
	if _, err := h.storage.Tags.AddPath(testUserID, tagID, "/docs/later.txt"); err != nil {
		t.Fatal(err)
	}

	result, err := h.service.Restore(testUserID, item.ID, false, trash.ConflictKeepBoth)
	if err != nil {
		t.Fatal(err)
	}
	const restoredPath = "/docs/file(1).txt"
	if result.Path != restoredPath {
		t.Fatalf("restore path = %q, want %q", result.Path, restoredPath)
	}
	assertFileContent(t, h.fs, testPath, "replacement")
	assertFileContent(t, h.fs, restoredPath, "original")
	restoredFavorite, err := h.storage.Favorites.GetByID(testUserID, favorite.ID)
	if err != nil {
		t.Fatal(err)
	}
	if restoredFavorite.Path != restoredPath {
		t.Fatalf("favorite path = %q", restoredFavorite.Path)
	}
	restoredTag, err := h.storage.Tags.GetByID(testUserID, tagID)
	if err != nil {
		t.Fatal(err)
	}
	if !equalStrings(restoredTag.Paths, []string{"/docs/later.txt", restoredPath}) {
		t.Fatalf("restored tag paths = %#v", restoredTag.Paths)
	}
}

func TestServiceReplaceMovesConflictIntoTrash(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, testPath, "original")
	original, err := h.service.Move(testUserID, testUserName, testPath)
	if err != nil {
		t.Fatal(err)
	}
	h.writeFile(t, testPath, "replacement")

	result, err := h.service.Restore(testUserID, original.ID, false, trash.ConflictReplace)
	if err != nil {
		t.Fatal(err)
	}
	if result.Path != testPath {
		t.Fatalf("restore result = %#v", result)
	}
	assertFileContent(t, h.fs, testPath, "original")
	items, err := h.storage.Trash.List(testUserID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].ID == original.ID || items[0].OriginalPath != testPath {
		t.Fatalf("trash items = %#v", items)
	}
	assertFileContent(t, h.fs, items[0].StoredPath, "replacement")
}

func TestServiceReplaceRestoresConflictWhenOriginalRestoreFails(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, testPath, "original")
	original, err := h.service.Move(testUserID, testUserName, testPath)
	if err != nil {
		t.Fatal(err)
	}
	h.writeFile(t, testPath, "replacement")
	failingFS := &failRenameFS{
		Fs: h.fs, from: original.StoredPath, to: testPath,
		err: errors.New("injected original restore failure"),
	}
	h.service.Fs = failingFS

	if _, err := h.service.Restore(testUserID, original.ID, false, trash.ConflictReplace); err == nil {
		t.Fatal("restore should report the injected rename failure")
	}
	assertFileContent(t, h.fs, testPath, "replacement")
	assertFileContent(t, h.fs, original.StoredPath, "original")
	items, err := h.storage.Trash.List(testUserID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].ID != original.ID || items[0].Status != trash.StatusAvailable {
		t.Fatalf("trash items after compensation = %#v", items)
	}
}

func TestServiceSkipAndPermanentDelete(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, testPath, "original")
	item, err := h.service.Move(testUserID, testUserName, testPath)
	if err != nil {
		t.Fatal(err)
	}
	h.writeFile(t, testPath, "replacement")

	result, err := h.service.Restore(testUserID, item.ID, false, trash.ConflictSkip)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Skipped || result.Path != testPath {
		t.Fatalf("skip result = %#v", result)
	}
	if _, err := h.storage.Trash.Get(testUserID, item.ID, false); err != nil {
		t.Fatalf("skipped item was removed: %v", err)
	}
	if err := h.service.DeletePermanent(testUserID, item.ID, false); err != nil {
		t.Fatal(err)
	}
	if exists, _ := afero.Exists(h.fs, item.StoredPath); exists {
		t.Fatal("permanently deleted content still exists")
	}
	if _, err := h.storage.Trash.Get(testUserID, item.ID, false); !errors.Is(err, trash.ErrNotExist) {
		t.Fatalf("trash record remains after permanent delete: %v", err)
	}
}

func TestServiceRejectsInternalPathAndPublicItemHidesPrivateState(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, "/.nas-file-browser-trash/private.txt", "private")
	if _, err := h.service.Move(testUserID, testUserName, "/.nas-file-browser-trash/private.txt"); !errors.Is(err, trash.ErrInvalidPath) {
		t.Fatalf("internal path error = %v", err)
	}

	item := &trash.Item{
		ID: "item", UserID: testUserID, StoredPath: "/hidden/private.txt",
		FavoriteSnapshots: []favorites.Favorite{{ID: "secret"}},
	}
	body, err := json.Marshal(item.Public())
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(body, []byte("StoredPath")) || bytes.Contains(body, []byte("storedPath")) ||
		bytes.Contains(body, []byte("hidden")) || bytes.Contains(body, []byte("secret")) {
		t.Fatalf("public payload leaked private state: %s", body)
	}
}

func assertFileContent(t *testing.T, filesystem afero.Fs, path, want string) {
	t.Helper()
	content, err := afero.ReadFile(filesystem, path)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != want {
		t.Fatalf("content at %s = %q, want %q", path, content, want)
	}
}

func equalStrings(left, right []string) bool {
	left = append([]string(nil), left...)
	right = append([]string(nil), right...)
	sort.Strings(left)
	sort.Strings(right)
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

type failRenameFS struct {
	afero.Fs
	from   string
	to     string
	err    error
	failed bool
}

func (filesystem *failRenameFS) Rename(from, to string) error {
	if !filesystem.failed && from == filesystem.from && to == filesystem.to {
		filesystem.failed = true
		return filesystem.err
	}
	return filesystem.Fs.Rename(from, to)
}
