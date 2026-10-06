package favorites

import (
	"errors"
	"testing"
)

func TestStorageKeepsFavoritesIsolatedByUser(t *testing.T) {
	backend := &memoryBackend{favorites: []*Favorite{
		{ID: "admin", UserID: 1, Path: "/documents", Name: "文档"},
		{ID: "guest", UserID: 2, Path: "/documents", Name: "文档"},
	}}
	storage := NewStorage(backend)

	favorites, err := storage.GetAll(1)
	if err != nil {
		t.Fatalf("读取收藏失败: %v", err)
	}
	if len(favorites) != 1 || favorites[0].ID != "admin" {
		t.Fatalf("用户 1 不应读取其他用户的收藏: %#v", favorites)
	}
}

func TestStorageRewritesAndRemovesPathPrefixesForEveryUser(t *testing.T) {
	backend := &memoryBackend{favorites: []*Favorite{
		{ID: "admin", UserID: 1, Path: "/docs/report.md", Name: "报告"},
		{ID: "guest", UserID: 2, Path: "/docs/team", Name: "团队"},
		{ID: "similar", UserID: 2, Path: "/docs-old/keep.md", Name: "保留"},
		{ID: "case", UserID: 1, Path: "/Docs/keep.md", Name: "区分大小写"},
	}}
	storage := NewStorage(backend)

	mutation, err := storage.RewritePathPrefix("/docs", "/archive")
	if err != nil {
		t.Fatalf("重写收藏路径失败: %v", err)
	}
	assertFavoritePath(t, backend.favorites, "admin", "/archive/report.md")
	assertFavoritePath(t, backend.favorites, "guest", "/archive/team")
	assertFavoritePath(t, backend.favorites, "similar", "/docs-old/keep.md")
	assertFavoritePath(t, backend.favorites, "case", "/Docs/keep.md")

	if err := storage.RestorePathMutation(mutation); err != nil {
		t.Fatalf("回滚收藏路径失败: %v", err)
	}
	assertFavoritePath(t, backend.favorites, "admin", "/docs/report.md")
	assertFavoritePath(t, backend.favorites, "guest", "/docs/team")

	if _, err := storage.RemovePathPrefix("/docs"); err != nil {
		t.Fatalf("清理收藏路径失败: %v", err)
	}
	if len(backend.favorites) != 2 {
		t.Fatalf("仅应保留相似前缀和大小写不同的收藏: %#v", backend.favorites)
	}
}

func TestDeletedSnapshotIsIndependentAndRestorable(t *testing.T) {
	backend := &failingFavoriteBackend{
		memoryBackend: &memoryBackend{
			favorites: []*Favorite{{ID: "favorite", UserID: 7, Path: "/docs/file.md"}},
		},
	}
	storage := NewStorage(backend)
	mutation, err := storage.RemovePathPrefix("/docs")
	if err != nil {
		t.Fatal(err)
	}
	snapshot := mutation.DeletedSnapshot()
	if len(snapshot) != 1 {
		t.Fatalf("snapshot = %#v", snapshot)
	}
	snapshot[0].Path = "/changed"
	if mutation.DeletedSnapshot()[0].Path != "/docs/file.md" {
		t.Fatal("snapshot mutated the original path mutation")
	}
	if err := storage.RestoreDeletedSnapshot(mutation.DeletedSnapshot()); err != nil {
		t.Fatal(err)
	}
	assertFavoritePath(t, backend.favorites, "favorite", "/docs/file.md")
}

func TestRestoreStagedSnapshotPreservesNewFavoriteAtDestination(t *testing.T) {
	backend := &memoryBackend{favorites: []*Favorite{
		{ID: "new", UserID: 7, Path: "/docs/file.md", Name: "新收藏"},
	}}
	storage := NewStorage(backend)
	restored, err := storage.RestoreStagedSnapshot([]Favorite{
		{ID: "staged", UserID: 7, Path: "/docs/file.md", Name: "旧收藏"},
		{ID: "other-user", UserID: 8, Path: "/docs/file.md", Name: "其他用户"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(restored) != 1 || restored[0].ID != "other-user" {
		t.Fatalf("restored rows = %#v", restored)
	}
	if len(backend.favorites) != 2 {
		t.Fatalf("favorites = %#v", backend.favorites)
	}
	assertFavoritePath(t, backend.favorites, "new", "/docs/file.md")
	assertFavoritePath(t, backend.favorites, "other-user", "/docs/file.md")
}

func TestPathMutationCompensatesPartialBackendFailuresWithoutDuplicates(t *testing.T) {
	original := []*Favorite{
		{ID: "first", UserID: 1, Path: "/docs/first.md"},
		{ID: "second", UserID: 2, Path: "/docs/second.md"},
	}

	rewriteBackend := &failingFavoriteBackend{
		memoryBackend: &memoryBackend{favorites: cloneFavorites(original)},
		failUpdateAt:  2,
	}
	if _, err := NewStorage(rewriteBackend).RewritePathPrefix("/docs", "/archive"); err == nil {
		t.Fatal("rewrite should report the injected backend failure")
	}
	assertFavoritePath(t, rewriteBackend.favorites, "first", "/docs/first.md")
	assertFavoritePath(t, rewriteBackend.favorites, "second", "/docs/second.md")

	deleteBackend := &failingFavoriteBackend{
		memoryBackend: &memoryBackend{favorites: cloneFavorites(original)},
		failDeleteAt:  2,
	}
	if _, err := NewStorage(deleteBackend).RemovePathPrefix("/docs"); err == nil {
		t.Fatal("removal should report the injected backend failure")
	}
	if len(deleteBackend.favorites) != len(original) {
		t.Fatalf("restored favorites = %#v; expected no duplicate rows", deleteBackend.favorites)
	}
	assertFavoritePath(t, deleteBackend.favorites, "first", "/docs/first.md")
	assertFavoritePath(t, deleteBackend.favorites, "second", "/docs/second.md")
}

func cloneFavorites(source []*Favorite) []*Favorite {
	cloned := make([]*Favorite, 0, len(source))
	for _, favorite := range source {
		copy := *favorite
		cloned = append(cloned, &copy)
	}
	return cloned
}

type failingFavoriteBackend struct {
	*memoryBackend
	updateCalls  int
	deleteCalls  int
	failUpdateAt int
	failDeleteAt int
}

func (m *failingFavoriteBackend) UpdatePath(id, path string) error {
	m.updateCalls++
	if m.updateCalls == m.failUpdateAt {
		return errors.New("injected favorite update failure")
	}
	return m.memoryBackend.UpdatePath(id, path)
}

func (m *failingFavoriteBackend) Delete(id string) error {
	m.deleteCalls++
	if m.deleteCalls == m.failDeleteAt {
		return errors.New("injected favorite delete failure")
	}
	return m.memoryBackend.Delete(id)
}

func assertFavoritePath(t *testing.T, favorites []*Favorite, id, want string) {
	t.Helper()
	for _, favorite := range favorites {
		if favorite.ID == id {
			if favorite.Path != want {
				t.Fatalf("收藏 %s 路径为 %q，期望 %q", id, favorite.Path, want)
			}
			return
		}
	}
	t.Fatalf("未找到收藏 %s", id)
}

type memoryBackend struct {
	favorites []*Favorite
}

func (m *memoryBackend) GetAll(userID uint) ([]*Favorite, error) {
	result := make([]*Favorite, 0)
	for _, favorite := range m.favorites {
		if favorite.UserID == userID {
			result = append(result, favorite)
		}
	}
	return result, nil
}
func (m *memoryBackend) GetAllForPathMutation() ([]*Favorite, error) {
	return append([]*Favorite(nil), m.favorites...), nil
}
func (m *memoryBackend) GetByID(userID uint, id string) (*Favorite, error) {
	for _, favorite := range m.favorites {
		if favorite.ID == id && favorite.UserID == userID {
			return favorite, nil
		}
	}
	return nil, ErrNotExist
}
func (m *memoryBackend) GetByPath(userID uint, path string) (*Favorite, error) {
	for _, favorite := range m.favorites {
		if favorite.Path == path && favorite.UserID == userID {
			return favorite, nil
		}
	}
	return nil, ErrNotExist
}
func (m *memoryBackend) Save(favorite *Favorite) error {
	copy := *favorite
	m.favorites = append(m.favorites, &copy)
	return nil
}
func (m *memoryBackend) Update(favorite *Favorite) error {
	for i, current := range m.favorites {
		if current.ID == favorite.ID {
			copy := *favorite
			m.favorites[i] = &copy
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) UpdatePath(id string, path string) error {
	for _, favorite := range m.favorites {
		if favorite.ID == id {
			favorite.Path = path
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) UpdateGroupID(id string, groupID string) error {
	for _, favorite := range m.favorites {
		if favorite.ID == id {
			favorite.GroupID = groupID
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) Delete(id string) error {
	for i, favorite := range m.favorites {
		if favorite.ID == id {
			m.favorites = append(m.favorites[:i], m.favorites[i+1:]...)
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) DeleteByPath(string) error                         { return nil }
func (m *memoryBackend) GetAllGroups(uint) ([]*FavoriteGroup, error)       { return nil, nil }
func (m *memoryBackend) GetGroupByID(uint, string) (*FavoriteGroup, error) { return nil, ErrNotExist }
func (m *memoryBackend) SaveGroup(*FavoriteGroup) error                    { return nil }
func (m *memoryBackend) UpdateGroup(*FavoriteGroup) error                  { return nil }
func (m *memoryBackend) DeleteGroup(string) error                          { return nil }
func (m *memoryBackend) ClaimLegacy(uint) error                            { return nil }
