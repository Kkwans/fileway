package transfers

import (
	"errors"
	"sort"
	"sync"
	"testing"
)

type memoryBackend struct {
	mu    sync.Mutex
	items map[string]*Item
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{items: make(map[string]*Item)}
}

func (backend *memoryBackend) GetAll() ([]*Item, error) {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	items := make([]*Item, 0, len(backend.items))
	for _, item := range backend.items {
		items = append(items, item.Clone())
	}
	return items, nil
}

func (backend *memoryBackend) GetByID(id string) (*Item, error) {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	item, ok := backend.items[id]
	if !ok {
		return nil, ErrNotExist
	}
	return item.Clone(), nil
}

func (backend *memoryBackend) Save(item *Item) error {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if _, exists := backend.items[item.ID]; exists {
		return errors.New("duplicate")
	}
	backend.items[item.ID] = item.Clone()
	return nil
}

func (backend *memoryBackend) Update(item *Item) error {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if _, exists := backend.items[item.ID]; !exists {
		return ErrNotExist
	}
	backend.items[item.ID] = item.Clone()
	return nil
}

func (backend *memoryBackend) Delete(id string) error {
	backend.mu.Lock()
	defer backend.mu.Unlock()
	if _, exists := backend.items[id]; !exists {
		return ErrNotExist
	}
	delete(backend.items, id)
	return nil
}

func TestStorageScopesAndUpdatesTransfers(t *testing.T) {
	storage := NewStorage(newMemoryBackend())
	upload, err := storage.New(1, KindUpload, "movie.mkv", "/movies/movie.mkv", 100)
	if err != nil {
		t.Fatal(err)
	}
	if upload.Status != StatusQueued {
		t.Fatalf("status = %s, want queued", upload.Status)
	}
	if _, err := storage.Progress(upload.ID, 2, 5); !errors.Is(err, ErrNotExist) {
		t.Fatalf("cross-user progress error = %v, want ErrNotExist", err)
	}
	updated, err := storage.Progress(upload.ID, 1, 40)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Status != StatusRunning || updated.BytesTransferred != 40 {
		t.Fatalf("progress = %#v", updated)
	}
	completed, err := storage.SetStatus(upload.ID, 1, StatusCompleted, "")
	if err != nil {
		t.Fatal(err)
	}
	if completed.BytesTransferred != 100 || completed.FinishedAt == 0 {
		t.Fatalf("completed = %#v", completed)
	}
	if _, err := storage.Get(2, upload.ID, false); !errors.Is(err, ErrNotExist) {
		t.Fatalf("cross-user get error = %v, want ErrNotExist", err)
	}
	items, err := storage.List(1, KindUpload, 0)
	if err != nil || len(items) != 1 || items[0].ID != upload.ID {
		t.Fatalf("list = %#v, err=%v", items, err)
	}
}

func TestStorageEnsuresCallerIDAndPrunesPerKind(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	item, err := storage.Ensure(1, "client-transfer", KindDownload, "x", "/x", 10)
	if err != nil {
		t.Fatal(err)
	}
	same, err := storage.Ensure(1, "client-transfer", KindDownload, "changed", "/changed", 20)
	if err != nil || same.Name != item.Name {
		t.Fatalf("ensure existing = %#v, err=%v", same, err)
	}
	for i := 0; i < MaxEntriesPerUser+3; i++ {
		created, createErr := storage.New(1, KindDownload, "item", "/item", 1)
		if createErr != nil {
			t.Fatal(createErr)
		}
		created.CreatedAt = int64(i + 1)
		if updateErr := backend.Update(created); updateErr != nil {
			t.Fatal(updateErr)
		}
	}
	items, err := storage.List(1, KindDownload, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != MaxEntriesPerUser {
		t.Fatalf("len(items) = %d, want %d", len(items), MaxEntriesPerUser)
	}
	if !sort.SliceIsSorted(items, func(i, j int) bool { return items[i].CreatedAt >= items[j].CreatedAt }) {
		t.Fatal("items are not sorted newest first")
	}
}

func TestStorageListPageUsesStableCursorAndScopedBatchDelete(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	for _, item := range []*Item{
		{ID: "new", UserID: 1, Kind: KindUpload, Name: "new", CreatedAt: 30},
		{ID: "same-z", UserID: 1, Kind: KindUpload, Name: "same-z", CreatedAt: 20},
		{ID: "same-a", UserID: 1, Kind: KindUpload, Name: "same-a", CreatedAt: 20},
		{ID: "other-user", UserID: 2, Kind: KindUpload, Name: "other-user", CreatedAt: 40},
		{ID: "other-kind", UserID: 1, Kind: KindDownload, Name: "other-kind", CreatedAt: 50},
	} {
		if err := backend.Update(item); err != nil {
			if err := backend.Save(item); err != nil {
				t.Fatal(err)
			}
		}
	}

	first, err := storage.ListPage(1, KindUpload, 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 2 || first[0].Name != "new" {
		t.Fatalf("first page = %#v", first)
	}
	next, err := storage.ListPage(1, KindUpload, 2, &RecentCursor{CreatedAt: first[1].CreatedAt, ID: first[1].ID})
	if err != nil {
		t.Fatal(err)
	}
	if len(next) != 1 || next[0].Name != "same-a" {
		t.Fatalf("next page = %#v", next)
	}
	if count, err := storage.Count(1, KindUpload); err != nil || count != 3 {
		t.Fatalf("count = %d, err=%v", count, err)
	}
	if deleted, err := storage.DeleteAll(1, KindUpload, false); err != nil || deleted != 3 {
		t.Fatalf("deleted = %d, err=%v", deleted, err)
	}
	if count, err := storage.Count(2, KindUpload); err != nil || count != 1 {
		t.Fatalf("other user count = %d, err=%v", count, err)
	}
	if count, err := storage.Count(1, KindDownload); err != nil || count != 1 {
		t.Fatalf("other kind count = %d, err=%v", count, err)
	}
}
