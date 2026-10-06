package tasks

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestRuntimeCompletesTaskWithDurableProgress(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	runtime, err := NewRuntime(storage)
	if err != nil {
		t.Fatal(err)
	}
	task, err := storage.New(1, "owner", TypeTrashClear, "清空回收站", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := runtime.Start(task, func(_ context.Context, report Reporter) (json.RawMessage, error) {
		return json.RawMessage(`{"ok":true}`), report(Progress{TotalItems: 3, ProcessedItems: 3, TotalBytes: 30, ProcessedBytes: 30})
	}); err != nil {
		t.Fatal(err)
	}

	completed := waitForTaskStatus(t, backend, task.ID, StatusCompleted)
	if completed.ProcessedItems != 3 || completed.ProcessedBytes != 30 || completed.FinishedAt == 0 {
		t.Fatalf("completed task = %#v", completed)
	}
	if string(completed.Result) != `{"ok":true}` {
		t.Fatalf("completed result = %s", completed.Result)
	}
}

func TestRuntimeCancellationStopsWorker(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	runtime, err := NewRuntime(storage)
	if err != nil {
		t.Fatal(err)
	}
	task, err := storage.New(1, "owner", TypeTrashClear, "清空回收站", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	if err := runtime.Start(task, func(ctx context.Context, _ Reporter) (json.RawMessage, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("worker did not start")
	}
	if _, err := runtime.Cancel(task.UserID, task.ID, false); err != nil {
		t.Fatal(err)
	}
	canceled := waitForTaskStatus(t, backend, task.ID, StatusCanceled)
	if canceled.CanRetry() || canceled.Error == "" {
		t.Fatalf("canceled task = %#v", canceled)
	}
}

func TestRuntimeStartAfterKeepsDestructiveTaskUndoable(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	runtime, err := NewRuntime(storage)
	if err != nil {
		t.Fatal(err)
	}
	task, err := storage.New(1, "owner", TypeFileDeletePermanent, "永久删除文件", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	if err := runtime.StartAfter(task, time.Now().Add(100*time.Millisecond), func(context.Context, Reporter) (json.RawMessage, error) {
		close(started)
		return nil, nil
	}); err != nil {
		t.Fatal(err)
	}
	queued := waitForTaskStatus(t, backend, task.ID, StatusQueued)
	if queued.Status != StatusQueued {
		t.Fatalf("delayed task status = %#v", queued)
	}
	if _, err := runtime.Cancel(task.UserID, task.ID, false); err != nil {
		t.Fatal(err)
	}
	waitForTaskStatus(t, backend, task.ID, StatusCanceled)
	select {
	case <-started:
		t.Fatal("destructive runner started after undo")
	default:
	}
}

func TestRuntimeStartAfterRunsAfterDeadline(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	runtime, err := NewRuntime(storage)
	if err != nil {
		t.Fatal(err)
	}
	task, err := storage.New(1, "owner", TypeTrashDeletePermanent, "永久删除回收站项目", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	if err := runtime.StartAfter(task, time.Now().Add(20*time.Millisecond), func(context.Context, Reporter) (json.RawMessage, error) {
		close(started)
		return nil, nil
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("delayed runner did not start")
	}
	waitForTaskStatus(t, backend, task.ID, StatusCompleted)
}

func TestRuntimeExclusiveKeyRejectsOverlappingDestructiveTask(t *testing.T) {
	backend := newMemoryBackend()
	storage := NewStorage(backend)
	runtime, err := NewRuntime(storage)
	if err != nil {
		t.Fatal(err)
	}
	first, err := storage.New(1, "first", TypeTrashClear, "first", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	second, err := storage.New(2, "second", TypeTrashClear, "second", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	if err := runtime.StartExclusive(first, func(ctx context.Context, _ Reporter) (json.RawMessage, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}, "trash.clear"); err != nil {
		t.Fatal(err)
	}
	<-started
	if err := runtime.StartExclusive(second, func(context.Context, Reporter) (json.RawMessage, error) { return nil, nil }, "trash.clear"); !errors.Is(err, ErrState) {
		t.Fatalf("overlapping start error = %v", err)
	}
	if _, err := runtime.Cancel(first.UserID, first.ID, false); err != nil {
		t.Fatal(err)
	}
	waitForTaskStatus(t, backend, first.ID, StatusCanceled)
	err = runtime.StartExclusive(second, func(context.Context, Reporter) (json.RawMessage, error) { return nil, nil }, "trash.clear")
	if err != nil {
		t.Fatalf("terminal task still held exclusive key: %v", err)
	}
	waitForTaskStatus(t, backend, second.ID, StatusCompleted)
}

func waitForTaskStatus(t *testing.T, backend *memoryBackend, id string, status Status) *Task {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		task, err := backend.GetByID(id)
		if err != nil {
			t.Fatal(err)
		}
		if task.Status == status {
			return task
		}
		time.Sleep(time.Millisecond)
	}
	task, _ := backend.GetByID(id)
	t.Fatalf("task did not reach %q: %#v", status, task)
	return nil
}
