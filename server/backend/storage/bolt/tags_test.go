package bolt

import (
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/asdine/storm/v3"
)

func TestTagsBackendClaimsLegacyRecordOnMutation(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "tags.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := tagsBackend{db: db}
	if err := backend.Save(&tags.Tag{ID: "legacy", Name: "工作", Color: "#1677ff"}); err != nil {
		t.Fatal(err)
	}

	tag, err := backend.GetByID(7, "legacy")
	if err != nil {
		t.Fatalf("旧标签应该可被当前账号接管: %v", err)
	}
	if tag.UserID != 7 {
		t.Fatalf("标签所有者未迁移: %d", tag.UserID)
	}
}
