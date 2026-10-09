package bolt

import (
	"fmt"
	"path/filepath"
	"sync"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/asdine/storm/v3"
)

func TestTagReferencesKeepPrivateBytesAndProvenanceAfterReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "owned-tag-wire.db")
	db, err := storm.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	original := &tags.Tag{ID: "owned", UserID: 7, Name: "标签", Paths: []string{"/\xd6\xd0.txt", "/中.txt", "/lost�", "/lost�"}, UnverifiedPaths: []bool{false, false, true, false}}
	if err := (tagsBackend{db}).Save(original); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = storm.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	}()
	loaded, err := (tagsBackend{db}).GetByID(7, "owned")
	if err != nil {
		t.Fatal(err)
	}
	for i, path := range original.Paths {
		if loaded.Paths[i] != path || loaded.PathIsUnverified(i) != original.PathIsUnverified(i) {
			t.Fatalf("index%d bytes/provenance lost=%#v", i, loaded)
		}
	}
}

func TestConcurrentTagReferencesKeepEveryOriginalByteIdentity(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "owned-tag-concurrent.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	}()
	store := tags.NewStorage(tagsBackend{db})
	tag, err := store.Create(7, "Owned tag", "#123456")
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	errors := make(chan error, 32)
	var done sync.WaitGroup
	for index := 0; index < 32; index++ {
		done.Add(1)
		go func(index int) {
			defer done.Done()
			<-start
			_, err := store.AddPath(7, tag.ID, fmt.Sprintf("/owned-%d-\xd6\xd0.txt", index))
			errors <- err
		}(index)
	}
	close(start)
	done.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	saved, err := store.GetByID(7, tag.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(saved.Paths) != 32 {
		t.Fatalf("concurrent links lost references: %d/32", len(saved.Paths))
	}
	seen := make(map[string]bool)
	for _, path := range saved.Paths {
		seen[path] = true
	}
	for index := 0; index < 32; index++ {
		if !seen[fmt.Sprintf("/owned-%d-\xd6\xd0.txt", index)] {
			t.Fatal("original bytes missing")
		}
	}
}
