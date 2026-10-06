package bolt

import (
	"encoding/json"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/asdine/storm/v3"
	bbolt "go.etcd.io/bbolt"

	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

func TestTaskBackendPersistsReplayAndClearsProgressFields(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "tasks.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := taskBackend{db: db}
	task := &tasks.Task{
		ID: "task", UserID: 7, OwnerName: "owner",
		Type: tasks.TypeTrashClear, Title: "清空回收站",
		Status: tasks.StatusFailed, CreatedAt: 10, StartedAt: 11,
		FinishedAt: 12, ArchivedAt: 13, TotalItems: 4, ProcessedItems: 2,
		TotalBytes: 100, ProcessedBytes: 50, Error: "disk failure",
		Args: json.RawMessage(`{"all":false}`), Result: json.RawMessage(`{"groups":2}`),
	}
	if err := backend.Save(task); err != nil {
		t.Fatal(err)
	}

	loaded, err := backend.GetByID(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if string(loaded.Args) != string(task.Args) || string(loaded.Result) != string(task.Result) || loaded.Error != task.Error {
		t.Fatalf("loaded task = %#v", loaded)
	}

	loaded.StartedAt = 0
	loaded.FinishedAt = 0
	loaded.ArchivedAt = 0
	loaded.TotalItems = 0
	loaded.ProcessedItems = 0
	loaded.TotalBytes = 0
	loaded.ProcessedBytes = 0
	loaded.Error = ""
	loaded.Result = nil
	loaded.Status = tasks.StatusQueued
	var beforeTxID int
	if err := db.Bolt.View(func(tx *bbolt.Tx) error {
		beforeTxID = tx.ID()
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := backend.Update(loaded); err != nil {
		t.Fatal(err)
	}
	if err := db.Bolt.View(func(tx *bbolt.Tx) error {
		if tx.ID() != beforeTxID+1 {
			t.Fatalf("task snapshot used %d durable commits, want 1", tx.ID()-beforeTxID)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	loaded, err = backend.GetByID(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.StartedAt != 0 || loaded.FinishedAt != 0 || loaded.ArchivedAt != 0 || loaded.TotalItems != 0 || loaded.Error != "" || len(loaded.Result) != 0 {
		t.Fatalf("zero fields were not persisted: %#v", loaded)
	}
}

func TestTaskBackendPersistsMediaAcrossReopenAndClearsIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "media.db")
	db, err := storm.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	backend := taskBackend{db: db}
	task := &tasks.Task{ID: "media", UserID: 7, Type: tasks.TypeMediaTranscode, Status: tasks.StatusRunning, CreatedAt: 10,
		SourcePath: "/电影/测试 ? #.mkv", OutputPath: "/电影/测试 ? #.source.mp4",
		Media: &hls.Progress{Phase: "encoding", DurationSeconds: 300, ProcessedSeconds: 40, PlayableSeconds: 36, Speed: 3.2, FPS: 76.8, StartedAt: 1000, UpdatedAt: 2000, AdvancedAt: 2000, Method: "hardware"}}
	if err := backend.Save(task); err != nil {
		t.Fatal(err)
	}
	task.Media.ProcessedSeconds = 80
	if err := backend.Update(task); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = storm.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})
	backend = taskBackend{db: db}
	loaded, err := backend.GetByID(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(loaded.Media, task.Media) || loaded.SourcePath != task.SourcePath || loaded.OutputPath != task.OutputPath {
		t.Fatalf("media metadata lost: %#v", loaded)
	}
	for _, read := range []func() ([]*tasks.Task, error){backend.GetAll, func() ([]*tasks.Task, error) { return backend.ListRecent(7, tasks.TypeMediaTranscode, 10, nil) }} {
		items, err := read()
		if err != nil || len(items) != 1 || !reflect.DeepEqual(items[0].Media, task.Media) || items[0].OutputPath != task.OutputPath {
			t.Fatalf("media list lost: %#v %v", items, err)
		}
	}
	loaded.Media = nil
	loaded.SourcePath, loaded.OutputPath = "", ""
	if err := backend.Update(loaded); err != nil {
		t.Fatal(err)
	}
	cleared, err := backend.GetByID(task.ID)
	if err != nil || cleared.Media != nil || cleared.SourcePath != "" || cleared.OutputPath != "" {
		t.Fatalf("media metadata not cleared: %#v %v", cleared, err)
	}
}

func TestTaskBackendListsRecentAnalysisWithStableCursorAndArchiveFilter(t *testing.T) {
	db, err := storm.Open(filepath.Join(t.TempDir(), "tasks.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})

	backend := taskBackend{db: db}
	for _, task := range []*tasks.Task{
		{ID: "new", UserID: 7, Type: tasks.TypeStorageAnalysis, Status: tasks.StatusCompleted, CreatedAt: 30},
		{ID: "same-z", UserID: 7, Type: tasks.TypeStorageAnalysis, Status: tasks.StatusCompleted, CreatedAt: 20},
		{ID: "same-a", UserID: 7, Type: tasks.TypeStorageAnalysis, Status: tasks.StatusCompleted, CreatedAt: 20},
		{ID: "archived", UserID: 7, Type: tasks.TypeStorageAnalysis, Status: tasks.StatusCompleted, CreatedAt: 10, ArchivedAt: 1},
		{ID: "other-user", UserID: 8, Type: tasks.TypeStorageAnalysis, Status: tasks.StatusCompleted, CreatedAt: 40},
		{ID: "other-type", UserID: 7, Type: tasks.TypeDuplicateAnalysis, Status: tasks.StatusCompleted, CreatedAt: 50},
	} {
		if err := backend.Save(task); err != nil {
			t.Fatal(err)
		}
	}

	first, err := backend.ListRecent(7, tasks.TypeStorageAnalysis, 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 2 || first[0].ID != "new" || first[1].ID != "same-z" {
		t.Fatalf("first page = %#v", first)
	}
	next, err := backend.ListRecent(7, tasks.TypeStorageAnalysis, 2, &tasks.RecentCursor{CreatedAt: first[1].CreatedAt, ID: first[1].ID})
	if err != nil {
		t.Fatal(err)
	}
	if len(next) != 1 || next[0].ID != "same-a" {
		t.Fatalf("next page = %#v", next)
	}
}
