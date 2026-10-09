package bolt

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/asdine/storm/v3"
)

func TestLegacyMetadataDoesNotAttachToRealUnicodeSiblingRename(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "metadata-identity.db")
	var db *storm.DB
	reopen := func() {
		t.Helper()
		if db != nil {
			if err := db.Close(); err != nil {
				t.Fatal(err)
			}
			db = nil
		}
		var err error
		db, err = storm.Open(databasePath)
		if err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		if db != nil {
			if err := db.Close(); err != nil {
				t.Error(err)
			}
		}
	})
	reopen()
	source, destination := "/lost�.txt", "/renamed�.txt"
	if err := db.Save(&Favorite{ID: "legacy-favorite", UserID: 7, Path: source, Name: "old"}); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&Tag{ID: "legacy-tag", UserID: 7, Name: "old", Paths: []string{source}}); err != nil {
		t.Fatal(err)
	}
	favoritesStore := favorites.NewStorage(favoritesBackend{db})
	tagsStore := tags.NewStorage(tagsBackend{db})
	name, color := "edited label", "#1677ff"
	if _, err := favoritesStore.UpdateFields(7, "legacy-favorite", &name, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := tagsStore.UpdateFields(7, "legacy-tag", &name, &color); err != nil {
		t.Fatal(err)
	}
	reopen()
	favoritesStore = favorites.NewStorage(favoritesBackend{db})
	tagsStore = tags.NewStorage(tagsBackend{db})
	legacy, err := favoritesStore.GetByID(7, "legacy-favorite")
	if err != nil || !legacy.PathUnverified || legacy.Name != name || legacy.Path != source || legacy.UserID != 7 {
		t.Fatalf("non-path favorite edit certified/lost legacy row: %#v %v", legacy, err)
	}
	tag, err := tagsStore.GetByID(7, "legacy-tag")
	if err != nil || len(tag.Paths) != 1 || !tag.PathIsUnverified(0) || tag.Name != name || tag.Color != color || tag.UserID != 7 {
		t.Fatalf("non-path tag edit certified/lost legacy row: %#v %v", tag, err)
	}
	if _, err := (favoritesBackend{db}).GetByPath(7, source); !errors.Is(err, favorites.ErrNotExist) {
		t.Fatalf("unknown favorite claimed real file identity: %v", err)
	}
	if err := (favoritesBackend{db}).UpdatePath("legacy-favorite", "/wrong"); !errors.Is(err, favorites.ErrNotExist) {
		t.Fatalf("direct path rewrite certified unknown favorite: %v", err)
	}
	fresh, err := favoritesStore.Add(7, source, "real Unicode file", 1)
	if err != nil || fresh.PathUnverified || fresh.ID == legacy.ID {
		t.Fatalf("fresh real Unicode favorite failed: %#v %v", fresh, err)
	}
	tag, err = tagsStore.AddPath(7, "legacy-tag", source)
	if err != nil || len(tag.Paths) != 2 || !tag.PathIsUnverified(0) || tag.PathIsUnverified(1) {
		t.Fatalf("fresh Unicode tag reference swallowed old reference: %#v %v", tag, err)
	}
	if tag, err = tagsStore.AddPath(7, "legacy-tag", source); err != nil || len(tag.Paths) != 2 {
		t.Fatalf("trusted tag duplicate failed: %#v %v", tag, err)
	}
	reopen()
	favoritesStore = favorites.NewStorage(favoritesBackend{db})
	tagsStore = tags.NewStorage(tagsBackend{db})
	// Rename a real file whose valid UTF-8 name contains U+FFFD. Metadata must
	// follow this new trusted identity without claiming an old damaged reference.
	fileRoot := t.TempDir()
	if err := os.WriteFile(filepath.Join(fileRoot, source[1:]), []byte("owned Unicode sibling"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(fileRoot, source[1:]), filepath.Join(fileRoot, destination[1:])); err != nil {
		t.Fatal(err)
	}
	if _, err := favoritesStore.RewritePathPrefix(source, destination); err != nil {
		t.Fatal(err)
	}
	if _, err := tagsStore.RewritePathPrefix(source, destination); err != nil {
		t.Fatal(err)
	}
	reopen()
	favoritesStore = favorites.NewStorage(favoritesBackend{db})
	tagsStore = tags.NewStorage(tagsBackend{db})
	legacy, err = favoritesStore.GetByID(7, "legacy-favorite")
	if err != nil || legacy.Path != source || !legacy.PathUnverified {
		t.Fatalf("rename changed old unknown favorite: %#v %v", legacy, err)
	}
	actual, err := favoritesStore.GetByID(7, fresh.ID)
	if err != nil || actual.Path != destination || actual.PathUnverified {
		t.Fatalf("fresh Unicode favorite did not follow real rename: %#v %v", actual, err)
	}
	tag, err = tagsStore.GetByID(7, "legacy-tag")
	if err != nil || len(tag.Paths) != 2 || tag.Paths[0] != source || !tag.PathIsUnverified(0) || tag.Paths[1] != destination || tag.PathIsUnverified(1) || tag.Name != name || tag.Color != color {
		t.Fatalf("rename crossed tag provenance or overwrote name/color: %#v %v", tag, err)
	}
	if content, err := os.ReadFile(filepath.Join(fileRoot, destination[1:])); err != nil || string(content) != "owned Unicode sibling" {
		t.Fatalf("real Unicode sibling rename failed: %q %v", content, err)
	}
	if _, err := favoritesStore.RemovePathPrefix(source); err != nil {
		t.Fatal(err)
	}
	if _, err := tagsStore.RemovePathPrefix(source); err != nil {
		t.Fatal(err)
	}
	if _, err := favoritesStore.GetByID(7, "legacy-favorite"); err != nil {
		t.Fatal("path removal erased old unknown favorite")
	}
	tag, err = tagsStore.GetByID(7, "legacy-tag")
	if err != nil || len(tag.Paths) != 2 || !tag.PathIsUnverified(0) {
		t.Fatalf("path removal erased old unknown tag reference: %#v %v", tag, err)
	}
}

func TestTrashMetadataSnapshotRoundTripRestoresMixedReferenceProvenance(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "trash-metadata-identity.db")
	var db *storm.DB
	reopen := func() {
		t.Helper()
		if db != nil {
			if err := db.Close(); err != nil {
				t.Fatal(err)
			}
			db = nil
		}
		var err error
		db, err = storm.Open(databasePath)
		if err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		if db != nil {
			if err := db.Close(); err != nil {
				t.Error(err)
			}
		}
	})
	reopen()
	path := "/lost�.txt"
	if err := db.Save(&Favorite{ID: "legacy-favorite", UserID: 7, Path: path}); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&Tag{ID: "legacy-tag", UserID: 7, Name: "mixed", Paths: []string{path}}); err != nil {
		t.Fatal(err)
	}
	favoritesStore := favorites.NewStorage(favoritesBackend{db})
	tagsStore := tags.NewStorage(tagsBackend{db})
	fresh, err := favoritesStore.Add(7, path, "real", 1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tagsStore.AddPath(7, "legacy-tag", path); err != nil {
		t.Fatal(err)
	}
	favoriteMutation, err := favoritesStore.RemovePathPrefix(path)
	if err != nil {
		t.Fatal(err)
	}
	tagMutation, err := tagsStore.RemovePathPrefix(path)
	if err != nil {
		t.Fatal(err)
	}
	rawPath, rawName := "/\xff/\xfe.txt", "\xfe.txt"
	item := &trash.Item{ID: "owned-trash", UserID: 7, OriginalPath: path, StoredPath: "/.nas-file-browser-trash/owned-trash/lost�.txt", Status: trash.StatusAvailable,
		FavoriteSnapshots: append(favoriteMutation.DeletedSnapshot(), favorites.Favorite{ID: "raw-snapshot", UserID: 7, Path: rawPath, Name: rawName}),
		TagSnapshots:      append(tagMutation.UpdatedSnapshot(), tags.Tag{ID: "raw-tag-snapshot", UserID: 7, Name: rawName, Paths: []string{rawPath}}),
	}
	if err := (trashBackend{db}).Save(item); err != nil {
		t.Fatal(err)
	}
	reopen()
	loaded, err := (trashBackend{db}).GetByID(item.ID)
	if err != nil || len(loaded.TagSnapshots) != 2 || len(loaded.FavoriteSnapshots) != 2 {
		t.Fatalf("snapshot rows lost: %#v %v", loaded, err)
	}
	mixed := loaded.TagSnapshots[0]
	if len(mixed.Paths) != 2 || mixed.Paths[0] != path || mixed.Paths[1] != path || !mixed.PathIsUnverified(0) || mixed.PathIsUnverified(1) {
		t.Fatalf("snapshot lost parallel unknown/trusted identities: %#v", mixed)
	}
	if loaded.TagSnapshots[1].Paths[0] != rawPath || loaded.TagSnapshots[1].Name != rawName || loaded.FavoriteSnapshots[1].Path != rawPath || loaded.FavoriteSnapshots[1].Name != rawName {
		t.Fatalf("snapshot lost opaque bytes: %#v", loaded)
	}
	favoritesStore = favorites.NewStorage(favoritesBackend{db})
	tagsStore = tags.NewStorage(tagsBackend{db})
	if err := tagsStore.RestoreRemovedSnapshot(loaded.TagSnapshots, path, path); err != nil {
		t.Fatal(err)
	}
	if _, err := favoritesStore.RestoreStagedSnapshot(loaded.FavoriteSnapshots[:1]); err != nil {
		t.Fatal(err)
	}
	reopen()
	legacy, err := (favoritesBackend{db}).GetByID(7, "legacy-favorite")
	if err != nil || !legacy.PathUnverified || legacy.Path != path {
		t.Fatalf("restore changed old unknown favorite: %#v %v", legacy, err)
	}
	actual, err := (favoritesBackend{db}).GetByID(7, fresh.ID)
	if err != nil || actual.PathUnverified || actual.Path != path {
		t.Fatalf("restore merged fresh favorite into old identity: %#v %v", actual, err)
	}
	mixedTag, err := (tagsBackend{db}).GetByID(7, "legacy-tag")
	if err != nil || len(mixedTag.Paths) != 2 || mixedTag.Paths[0] != path || mixedTag.Paths[1] != path || !mixedTag.PathIsUnverified(0) || mixedTag.PathIsUnverified(1) {
		t.Fatalf("restore merged fresh tag path into old identity: %#v %v", mixedTag, err)
	}
}
