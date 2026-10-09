package favorites

import "testing"

func TestWorkspaceRemovalDuringRenameCompensatesFailure(t *testing.T) {
	backend := &failingFavoriteBackend{memoryBackend: &memoryBackend{favorites: []*Favorite{
		{ID: "leaves-workspace", UserID: 1, Path: "/old.txt"},
		{ID: "rename-fails", UserID: 2, Path: "/old.txt"},
	}}, failUpdateAt: 1}
	storage := NewStorage(backend)
	_, err := storage.RewritePathPrefix("/old.txt", "/new.txt", func(owner uint, path string) (string, bool) {
		if owner == 1 {
			return "", true
		}
		return "/new.txt", true
	})
	if err == nil {
		t.Fatal("expected injected rename metadata failure")
	}
	if len(backend.favorites) != 2 {
		t.Fatalf("removed row was not restored: %#v", backend.favorites)
	}
	assertFavoritePath(t, backend.favorites, "leaves-workspace", "/old.txt")
	assertFavoritePath(t, backend.favorites, "rename-fails", "/old.txt")
}
