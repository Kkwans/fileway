package bolt

import (
	"errors"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/asdine/storm/v3"
)

func TestFavoritesBackendClaimsLegacyRecordOnMutation(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "favorites.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := favoritesBackend{db: db}
	if err := backend.Save(&favorites.Favorite{ID: "legacy", Path: "/资料", Name: "资料"}); err != nil {
		t.Fatal(err)
	}

	favorite, err := backend.GetByID(7, "legacy")
	if err != nil {
		t.Fatalf("旧收藏应该可被当前账号接管: %v", err)
	}
	if favorite.UserID != 7 {
		t.Fatalf("收藏所有者未迁移: %d", favorite.UserID)
	}
}

func TestFavoritesBackendPersistsMovingFavoriteOutOfGroup(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "favorites.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := favoritesBackend{db: db}
	storage := favorites.NewStorage(backend)
	if err := backend.Save(&favorites.Favorite{
		ID:      "grouped",
		UserID:  7,
		Path:    "/资料",
		Name:    "资料",
		GroupID: "project",
	}); err != nil {
		t.Fatal(err)
	}

	ungrouped := ""
	if _, err := storage.UpdateFieldsEx(7, "grouped", nil, nil, &ungrouped); err != nil {
		t.Fatalf("移出分组失败: %v", err)
	}

	favorite, err := backend.GetByID(7, "grouped")
	if err != nil {
		t.Fatal(err)
	}
	if favorite.GroupID != "" {
		t.Fatalf("空分组未持久化: %q", favorite.GroupID)
	}
}

func TestFavoritesBackendDeletesGroupAndUngroupsFavoritesInOneTransaction(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "favorites.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := favoritesBackend{db: db}
	storage := favorites.NewStorage(backend)
	if err := backend.SaveGroup(&favorites.FavoriteGroup{ID: "project", UserID: 7, Name: "项目"}); err != nil {
		t.Fatal(err)
	}
	for _, favorite := range []*favorites.Favorite{
		{ID: "one", UserID: 7, Path: "/one", GroupID: "project"},
		{ID: "two", UserID: 7, Path: "/two", GroupID: "project"},
		{ID: "other", UserID: 8, Path: "/other", GroupID: "project"},
	} {
		if err := backend.Save(favorite); err != nil {
			t.Fatal(err)
		}
	}

	if err := storage.DeleteGroup(7, "project"); err != nil {
		t.Fatalf("删除分组失败: %v", err)
	}
	if _, err := backend.GetGroupByID(7, "project"); !errors.Is(err, favorites.ErrNotExist) {
		t.Fatalf("分组仍存在: %v", err)
	}
	for _, id := range []string{"one", "two"} {
		favorite, err := backend.GetByID(7, id)
		if err != nil {
			t.Fatal(err)
		}
		if favorite.GroupID != "" {
			t.Fatalf("收藏 %s 仍属于已删除分组: %q", id, favorite.GroupID)
		}
	}
	other, err := backend.GetByID(8, "other")
	if err != nil {
		t.Fatal(err)
	}
	if other.GroupID != "project" {
		t.Fatalf("其他用户收藏被错误改写: %q", other.GroupID)
	}
}
