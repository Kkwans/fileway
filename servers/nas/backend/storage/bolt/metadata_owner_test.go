package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMetadataBackendsPersistUserOwnership(t *testing.T) {
	db := openMetadataTestDB(t)
	favoriteBackend := favoritesBackend{db: db}
	tagBackend := tagsBackend{db: db}

	if err := favoriteBackend.Save(&favorites.Favorite{ID: "favorite", UserID: 7, Path: "/docs"}); err != nil {
		t.Fatal(err)
	}
	if err := favoriteBackend.SaveGroup(&favorites.FavoriteGroup{ID: "group", UserID: 7, Name: "工作"}); err != nil {
		t.Fatal(err)
	}
	if err := tagBackend.Save(&tags.Tag{ID: "tag", UserID: 7, Name: "工作", Paths: []string{"/docs"}}); err != nil {
		t.Fatal(err)
	}

	favorite, err := favoriteBackend.GetByID(7, "favorite")
	if err != nil {
		t.Fatal(err)
	}
	group, err := favoriteBackend.GetGroupByID(7, "group")
	if err != nil {
		t.Fatal(err)
	}
	tag, err := tagBackend.GetByID(7, "tag")
	if err != nil {
		t.Fatal(err)
	}
	if favorite.UserID != 7 || group.UserID != 7 || tag.UserID != 7 {
		t.Fatalf("owners = favorite:%d group:%d tag:%d", favorite.UserID, group.UserID, tag.UserID)
	}

	allFavorites, err := favoriteBackend.GetAllForPathMutation()
	if err != nil {
		t.Fatal(err)
	}
	allTags, err := tagBackend.GetAllForPathMutation()
	if err != nil {
		t.Fatal(err)
	}
	if len(allFavorites) != 1 || allFavorites[0].UserID != 7 {
		t.Fatalf("favorites for path mutation = %#v", allFavorites)
	}
	if len(allTags) != 1 || allTags[0].UserID != 7 {
		t.Fatalf("tags for path mutation = %#v", allTags)
	}
}

func TestNewStorageMigratesIndexedOwnersMissingFromLegacyJSON(t *testing.T) {
	db := openMetadataTestDB(t)
	if err := db.Save(&users.User{ID: 7, Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	// These public domain structs reproduce the previous persistence format:
	// Storm indexes UserID before JSON encoding, while json:"-" omits it from
	// the stored value.
	if err := db.Save(&favorites.Favorite{ID: "favorite", UserID: 7, Path: "/docs"}); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&favorites.FavoriteGroup{ID: "group", UserID: 7, Name: "工作"}); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&tags.Tag{ID: "tag", UserID: 7, Name: "工作", Paths: []string{"/docs"}}); err != nil {
		t.Fatal(err)
	}

	storage, err := NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	favorite, err := storage.Favorites.GetByID(7, "favorite")
	if err != nil {
		t.Fatal(err)
	}
	groups, err := storage.Favorites.GetAllGroups(7)
	if err != nil {
		t.Fatal(err)
	}
	tag, err := storage.Tags.GetByID(7, "tag")
	if err != nil {
		t.Fatal(err)
	}
	if favorite.UserID != 7 || len(groups) != 1 || groups[0].UserID != 7 || tag.UserID != 7 {
		t.Fatalf("migrated owners = favorite:%#v groups:%#v tag:%#v", favorite, groups, tag)
	}

	// A second open is idempotent and reads ownership from the encoded rows,
	// not merely from the old index.
	if _, err := NewStorage(db); err != nil {
		t.Fatal(err)
	}
	allFavorites, err := favoritesBackend{db: db}.GetAllForPathMutation()
	if err != nil {
		t.Fatal(err)
	}
	allTags, err := tagsBackend{db: db}.GetAllForPathMutation()
	if err != nil {
		t.Fatal(err)
	}
	if len(allFavorites) != 1 || allFavorites[0].UserID != 7 || len(allTags) != 1 || allTags[0].UserID != 7 {
		t.Fatalf("persisted migration = favorites:%#v tags:%#v", allFavorites, allTags)
	}
}

func openMetadataTestDB(t *testing.T) *storm.DB {
	t.Helper()
	db, err := storm.Open(filepath.Join(t.TempDir(), "metadata.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})
	return db
}
