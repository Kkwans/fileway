package trash

import (
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
)

func TestStorageEnforcesOwnershipAndAdminVisibility(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	first := &Item{ID: "first", UserID: 1, OwnerName: "first-user", DeletedAt: 10, Status: StatusAvailable}
	second := &Item{ID: "second", UserID: 2, OwnerName: "second-user", DeletedAt: 20, Status: StatusAvailable}
	if err := storage.Save(first); err != nil {
		t.Fatal(err)
	}
	if err := storage.Save(second); err != nil {
		t.Fatal(err)
	}

	items, err := storage.List(1, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].ID != first.ID {
		t.Fatalf("user list = %#v", items)
	}
	if _, err := storage.Get(1, second.ID, false); err != ErrForbidden {
		t.Fatalf("cross-user get error = %v", err)
	}
	if err := storage.Delete(1, second.ID, false); err != ErrForbidden {
		t.Fatalf("cross-user delete error = %v", err)
	}

	items, err = storage.List(1, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 2 || items[0].ID != second.ID || items[1].ID != first.ID {
		t.Fatalf("admin list = %#v", items)
	}
}

func TestItemCloneKeepsMetadataSnapshotsIndependent(t *testing.T) {
	original := &Item{
		ID:                "item",
		FavoriteSnapshots: []favorites.Favorite{{ID: "favorite", Path: "/docs"}},
		TagSnapshots:      []tags.Tag{{ID: "tag", Paths: []string{"/docs", "/docs/file"}}},
	}
	clone := original.Clone()
	clone.FavoriteSnapshots[0].Path = "/changed"
	clone.TagSnapshots[0].Paths[0] = "/changed"

	if original.FavoriteSnapshots[0].Path != "/docs" {
		t.Fatalf("favorite snapshot was mutated: %#v", original.FavoriteSnapshots)
	}
	if original.TagSnapshots[0].Paths[0] != "/docs" {
		t.Fatalf("tag snapshot was mutated: %#v", original.TagSnapshots)
	}
}

type memoryBackend struct {
	items map[string]*Item
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{items: make(map[string]*Item)}
}

func (backend *memoryBackend) GetAll() ([]*Item, error) {
	items := make([]*Item, 0, len(backend.items))
	for _, item := range backend.items {
		items = append(items, item.Clone())
	}
	return items, nil
}

func (backend *memoryBackend) GetByID(id string) (*Item, error) {
	item, ok := backend.items[id]
	if !ok {
		return nil, ErrNotExist
	}
	return item.Clone(), nil
}

func (backend *memoryBackend) Save(item *Item) error {
	backend.items[item.ID] = item.Clone()
	return nil
}

func (backend *memoryBackend) Update(item *Item) error {
	if _, ok := backend.items[item.ID]; !ok {
		return ErrNotExist
	}
	backend.items[item.ID] = item.Clone()
	return nil
}

func (backend *memoryBackend) Delete(id string) error {
	if _, ok := backend.items[id]; !ok {
		return ErrNotExist
	}
	delete(backend.items, id)
	return nil
}
