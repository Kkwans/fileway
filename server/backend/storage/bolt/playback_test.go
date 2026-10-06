package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/playback"
)

func TestPlaybackBackendUpdatesZeroPositionAndDurationInOneRecord(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "playback.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := playbackBackend{db: db}
	entry := &playback.Entry{
		ID: "playback", UserID: 7, Path: "/video.mp4", Identity: "v1:10:20",
		Position: 42.5, Duration: 90, UpdatedAt: 1,
	}
	if err := backend.Save(entry); err != nil {
		t.Fatal(err)
	}
	entry.Identity = "v1:10:21"
	entry.Position = 0
	entry.Duration = 0
	entry.UpdatedAt = 2
	if err := backend.Update(entry); err != nil {
		t.Fatal(err)
	}

	loaded, err := backend.GetByID(entry.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Identity != entry.Identity || loaded.Position != 0 || loaded.Duration != 0 || loaded.UpdatedAt != 2 {
		t.Fatalf("updated playback = %#v", loaded)
	}
}
