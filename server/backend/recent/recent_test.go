package recent

import (
	"encoding/json"
	"errors"
	"fmt"
	"testing"
)

func TestRecentIsPrivateDeduplicatedBoundedAndHidesOwner(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	first, err := storage.Record(1, "/docs/report.md", "report.md", false)
	if err != nil {
		t.Fatal(err)
	}
	updated, err := storage.Record(1, "/docs/report.md", "report.md", false)
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != updated.ID || updated.AccessedAt <= first.AccessedAt {
		t.Fatalf("deduplicated entry = %#v after %#v", updated, first)
	}
	for index := 0; index < MaxEntriesPerUser+1; index++ {
		if _, err := storage.Record(1, fmt.Sprintf("/file-%03d", index), "", false); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := storage.Record(2, "/private", "private", true); err != nil {
		t.Fatal(err)
	}
	entries, err := storage.List(1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != MaxEntriesPerUser {
		t.Fatalf("recent length = %d", len(entries))
	}
	for _, entry := range entries {
		if entry.UserID != 1 {
			t.Fatalf("cross-user entry leaked: %#v", entry)
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
		t.Fatalf("owner leaked: %s", payload)
	}
}

func TestRecentPathMutationsHonorBoundariesAndRollback(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	for _, value := range []string{"/docs", "/docs/a.md", "/docs-old/a.md", "/Docs/a.md"} {
		if _, err := storage.Record(1, value, "", false); err != nil {
			t.Fatal(err)
		}
	}
	mutation, err := storage.RewritePathPrefix("/docs", "/archive")
	if err != nil {
		t.Fatal(err)
	}
	entries, _ := storage.List(1, 100)
	paths := entryPaths(entries)
	for _, expected := range []string{"/archive", "/archive/a.md", "/docs-old/a.md", "/Docs/a.md"} {
		if !paths[expected] {
			t.Fatalf("missing rewritten path %q in %#v", expected, paths)
		}
	}
	if err := storage.RestorePathMutation(mutation); err != nil {
		t.Fatal(err)
	}
	removed, err := storage.RemovePathPrefix("/docs")
	if err != nil {
		t.Fatal(err)
	}
	entries, _ = storage.List(1, 100)
	paths = entryPaths(entries)
	if paths["/docs"] || paths["/docs/a.md"] || !paths["/docs-old/a.md"] || !paths["/Docs/a.md"] {
		t.Fatalf("boundary removal paths = %#v", paths)
	}
	if err := storage.RestorePathMutation(removed); err != nil {
		t.Fatal(err)
	}
	entries, _ = storage.List(1, 100)
	if len(entries) != 4 {
		t.Fatalf("restored entries = %#v", entries)
	}
}

func entryPaths(entries []*Entry) map[string]bool {
	paths := make(map[string]bool, len(entries))
	for _, entry := range entries {
		paths[entry.Path] = true
	}
	return paths
}

type memoryBackend struct {
	items map[string]*Entry
}

func newMemoryBackend() *memoryBackend {
	return &memoryBackend{items: make(map[string]*Entry)}
}

func (backend *memoryBackend) GetAll() ([]*Entry, error) {
	entries := make([]*Entry, 0, len(backend.items))
	for _, entry := range backend.items {
		entries = append(entries, entry.Clone())
	}
	return entries, nil
}

func (backend *memoryBackend) Save(entry *Entry) error {
	if _, exists := backend.items[entry.ID]; exists {
		return errors.New("duplicate recent entry")
	}
	backend.items[entry.ID] = entry.Clone()
	return nil
}

func (backend *memoryBackend) Update(entry *Entry) error {
	if _, exists := backend.items[entry.ID]; !exists {
		return errors.New("missing recent entry")
	}
	backend.items[entry.ID] = entry.Clone()
	return nil
}

func (backend *memoryBackend) Delete(id string) error {
	if _, exists := backend.items[id]; !exists {
		return errors.New("missing recent entry")
	}
	delete(backend.items, id)
	return nil
}
