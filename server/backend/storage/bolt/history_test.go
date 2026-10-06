package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/history"
)

func TestHistoryBackendPersistsPrivateOwner(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "history.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := historyBackend{db: db}
	entry := &history.Entry{
		ID: "entry", UserID: 7, Action: "trash.restore",
		Target: "/资料/文档.md", Status: history.StatusSuccess, CreatedAt: 10,
	}
	if err := backend.Save(entry); err != nil {
		t.Fatal(err)
	}
	entries, err := backend.GetByUser(entry.UserID)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].UserID != entry.UserID || entries[0].Target != entry.Target {
		t.Fatalf("persisted history = %#v", entries)
	}
	other, err := backend.GetByUser(8)
	if err != nil {
		t.Fatal(err)
	}
	if len(other) != 0 {
		t.Fatalf("cross-user history = %#v", other)
	}
}
