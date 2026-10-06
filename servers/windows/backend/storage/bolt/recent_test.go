package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/recent"
)

func TestRecentBackendPersistsPrivateOwnerAndUpdatesZeroValues(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "recent.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := recentBackend{db: db}
	entry := &recent.Entry{
		ID: "recent", UserID: 7, Path: "/资料", Name: "资料",
		IsDir: true, AccessedAt: 10,
	}
	if err := backend.Save(entry); err != nil {
		t.Fatal(err)
	}
	entry.IsDir = false
	entry.Name = "资料.md"
	entry.Path = "/资料.md"
	entry.AccessedAt = 11
	if err := backend.Update(entry); err != nil {
		t.Fatal(err)
	}
	entries, err := backend.GetAll()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].UserID != 7 || entries[0].IsDir || entries[0].Path != "/资料.md" {
		t.Fatalf("persisted recent = %#v", entries)
	}
}
