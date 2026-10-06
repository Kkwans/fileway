package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/trash"
)

func TestTrashBackendPersistsMetadataSnapshots(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "trash.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := trashBackend{db: db}
	item := &trash.Item{
		ID:           "trash-item",
		UserID:       7,
		OriginalPath: "/资料/文档.md",
		StoredPath:   "/.nas-file-browser-trash/trash-item/文档.md",
		Status:       trash.StatusAvailable,
		FavoriteSnapshots: []favorites.Favorite{{
			ID: "favorite", UserID: 7, Path: "/资料/文档.md",
		}},
		TagSnapshots: []tags.Tag{{
			ID: "tag", UserID: 7, Paths: []string{"/资料/文档.md"},
		}},
	}
	if err := backend.Save(item); err != nil {
		t.Fatal(err)
	}

	loaded, err := backend.GetByID(item.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(loaded.FavoriteSnapshots) != 1 || loaded.FavoriteSnapshots[0].Path != item.OriginalPath {
		t.Fatalf("favorite snapshots = %#v", loaded.FavoriteSnapshots)
	}
	if loaded.FavoriteSnapshots[0].UserID != item.UserID {
		t.Fatalf("favorite owner = %d, want %d", loaded.FavoriteSnapshots[0].UserID, item.UserID)
	}
	if len(loaded.TagSnapshots) != 1 || len(loaded.TagSnapshots[0].Paths) != 1 || loaded.TagSnapshots[0].Paths[0] != item.OriginalPath {
		t.Fatalf("tag snapshots = %#v", loaded.TagSnapshots)
	}
	if loaded.TagSnapshots[0].UserID != item.UserID {
		t.Fatalf("tag owner = %d, want %d", loaded.TagSnapshots[0].UserID, item.UserID)
	}

	loaded.Status = trash.StatusFailed
	loaded.LastError = "restore failed"
	if err := backend.Update(loaded); err != nil {
		t.Fatal(err)
	}
	loaded.LastError = ""
	if err := backend.Update(loaded); err != nil {
		t.Fatal(err)
	}
	loaded, err = backend.GetByID(item.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.LastError != "" {
		t.Fatalf("last error was not cleared: %q", loaded.LastError)
	}
}
