package bolt

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/uploads"
	"github.com/asdine/storm/v3"
)

func TestUploadSessionSurvivesDatabaseReopenWithOpaquePathAndZeroCheckpoint(t *testing.T) {
	file := filepath.Join(t.TempDir(), "owned.db")
	db, err := storm.Open(file)
	if err != nil {
		t.Fatal(err)
	}
	store, err := NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	row := &uploads.Session{ID: "owned-upload", UserID: 1, WirePath: "/%D6%D0/a%252F.bin", PartWire: "/%D6%D0/.fileway-upload-owned.part", RootKey: "owned-root", Length: 8, Offset: 4, State: uploads.Active}
	uploads.Touch(row, time.Now())
	if err := store.Uploads.Create(row); err != nil {
		t.Fatal(err)
	}
	row.Offset = 0
	row.Writing = true
	row.ReservedTo = 4
	if err := store.Uploads.Save(row); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = storm.Open(file)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	store, err = NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	saved, err := store.Uploads.Get(row.ID, 1, row.WirePath, row.RootKey)
	if err != nil || saved.Offset != 0 || !saved.Writing || saved.ReservedTo != 4 || saved.PartWire != row.PartWire {
		t.Fatal(saved, err)
	}
	if _, err := store.Transfers.Get(1, row.ID, false); err == nil {
		t.Fatal("upload session incorrectly shares removable history rows")
	}
}
