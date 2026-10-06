package bolt

import (
	"path/filepath"
	"testing"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/transfers"
)

func TestTransferBackendListsRecentWithStableCursorAndCounts(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "transfers.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })

	backend := transferBackend{db: db}
	for _, item := range []*transfers.Item{
		{ID: "new", UserID: 7, Kind: transfers.KindUpload, Name: "new", CreatedAt: 30, BatchID: "folder-1", BatchName: "测试文件夹", BatchItems: 3, BatchBytes: 99, IsFolderUpload: true},
		{ID: "same-z", UserID: 7, Kind: transfers.KindUpload, Name: "same-z", CreatedAt: 20},
		{ID: "same-a", UserID: 7, Kind: transfers.KindUpload, Name: "same-a", CreatedAt: 20},
		{ID: "archived-like", UserID: 7, Kind: transfers.KindDownload, Name: "other-kind", CreatedAt: 40},
		{ID: "other-user", UserID: 8, Kind: transfers.KindUpload, Name: "other-user", CreatedAt: 50},
	} {
		if err := backend.Save(item); err != nil {
			t.Fatal(err)
		}
	}
	loaded, err := backend.GetByID("new")
	if err != nil || loaded.BatchID != "folder-1" || loaded.BatchName != "测试文件夹" || loaded.BatchItems != 3 || loaded.BatchBytes != 99 || !loaded.IsFolderUpload {
		t.Fatalf("batch metadata = %#v, err=%v", loaded, err)
	}

	first, err := backend.ListRecent(7, transfers.KindUpload, 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 2 || first[0].ID != "new" || first[1].ID != "same-z" {
		t.Fatalf("first page = %#v", first)
	}
	next, err := backend.ListRecent(7, transfers.KindUpload, 2, &transfers.RecentCursor{CreatedAt: first[1].CreatedAt, ID: first[1].ID})
	if err != nil {
		t.Fatal(err)
	}
	if len(next) != 1 || next[0].ID != "same-a" {
		t.Fatalf("next page = %#v", next)
	}
	count, err := backend.Count(7, transfers.KindUpload)
	if err != nil || count != 3 {
		t.Fatalf("count = %d, err=%v", count, err)
	}
}
