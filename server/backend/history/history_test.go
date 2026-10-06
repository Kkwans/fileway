package history

import (
	"encoding/json"
	"errors"
	"testing"
)

func TestHistoryIsPrivateBoundedAndHidesOwner(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	for index := 0; index < MaxEntriesPerUser+1; index++ {
		if _, err := storage.Record(1, "trash.move", "/document.txt", "", StatusSuccess); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := storage.Record(2, "trash.restore", "/private.txt", "", StatusSuccess); err != nil {
		t.Fatal(err)
	}

	entries, err := storage.List(1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != MaxEntriesPerUser {
		t.Fatalf("history length = %d", len(entries))
	}
	for _, entry := range entries {
		if entry.UserID != 1 {
			t.Fatalf("cross-user history leaked: %#v", entry)
		}
	}
	payload, err := json.Marshal(entries[0])
	if err != nil {
		t.Fatal(err)
	}
	var public map[string]interface{}
	if err := json.Unmarshal(payload, &public); err != nil {
		t.Fatal(err)
	}
	if _, leaked := public["userId"]; leaked {
		t.Fatalf("history owner leaked: %s", payload)
	}
}

func TestHistoryLimitIsCapped(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	for index := 0; index < 5; index++ {
		if _, err := storage.Record(1, "file.rename", "/file", "", StatusSuccess); err != nil {
			t.Fatal(err)
		}
	}
	entries, err := storage.List(1, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("limited history length = %d", len(entries))
	}
}

type memoryBackend struct {
	items map[string]*Entry
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{items: make(map[string]*Entry)}
}

func (backend *memoryBackend) GetByUser(userID uint) ([]*Entry, error) {
	entries := make([]*Entry, 0)
	for _, entry := range backend.items {
		if entry.UserID == userID {
			entries = append(entries, entry.Clone())
		}
	}
	return entries, nil
}

func (backend *memoryBackend) Save(entry *Entry) error {
	backend.items[entry.ID] = entry.Clone()
	return nil
}

func (backend *memoryBackend) Delete(id string) error {
	if _, exists := backend.items[id]; !exists {
		return errors.New("missing history entry")
	}
	delete(backend.items, id)
	return nil
}
