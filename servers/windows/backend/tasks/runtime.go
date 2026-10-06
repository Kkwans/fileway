package tasks

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"
)

type Progress struct {
	TotalItems     int
	ProcessedItems int
	TotalBytes     int64
	ProcessedBytes int64
	// Checkpoint is an optional durable file-task checkpoint. It is persisted
	// immediately, while ordinary progress is throttled by Runtime.
	Checkpoint json.RawMessage
}

type Reporter func(progress Progress) error
type Runner func(ctx context.Context, report Reporter) (json.RawMessage, error)

// Runtime coordinates only active in-process workers. Durable task state and
// restart semantics remain in Storage so task history survives process exits.
type Runtime struct {
	storage  *Storage
	mu       sync.Mutex
	cancels  map[string]context.CancelFunc
	keys     map[string]string
	taskKeys map[string][]string
}

func NewRuntime(storage *Storage) (*Runtime, error) {
	if storage == nil {
		return nil, fmt.Errorf("task storage is not configured")
	}
	if err := storage.InterruptActive(); err != nil {
		return nil, fmt.Errorf("mark active tasks interrupted: %w", err)
	}
	return &Runtime{
		storage: storage, cancels: make(map[string]context.CancelFunc),
		keys: make(map[string]string), taskKeys: make(map[string][]string),
	}, nil
}

func (runtime *Runtime) Start(task *Task, runner Runner) error {
	return runtime.start(task, runner, nil)
}

// StartAfter keeps a task queued until the deadline. Cancellation during the
// delay marks it canceled without invoking the destructive runner.
func (runtime *Runtime) StartAfter(task *Task, at time.Time, runner Runner, keys ...string) error {
	if task == nil || task.Status != StatusQueued || runner == nil {
		return ErrState
	}
	ctx, cancel := context.WithCancel(context.Background())
	runtime.mu.Lock()
	if _, exists := runtime.cancels[task.ID]; exists {
		runtime.mu.Unlock()
		cancel()
		return ErrState
	}
	for _, key := range keys {
		if key == "" {
			continue
		}
		if _, exists := runtime.keys[key]; exists {
			runtime.mu.Unlock()
			cancel()
			return ErrState
		}
	}
	runtime.cancels[task.ID] = cancel
	for _, key := range keys {
		if key != "" {
			runtime.keys[key] = task.ID
			runtime.taskKeys[task.ID] = append(runtime.taskKeys[task.ID], key)
		}
	}
	runtime.mu.Unlock()

	go func() {
		defer runtime.release(task.ID)
		delay := time.Until(at)
		if delay > 0 {
			timer := time.NewTimer(delay)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				runtime.markCanceled(task)
				return
			case <-timer.C:
			}
		}
		if ctx.Err() != nil {
			runtime.markCanceled(task)
			return
		}
		runtime.run(ctx, task.Clone(), runner)
	}()
	return nil
}

func (runtime *Runtime) markCanceled(task *Task) {
	clone := task.Clone()
	clone.Status = StatusCanceled
	clone.FinishedAt = time.Now().UnixMilli()
	clone.Error = "任务已取消"
	_ = runtime.storage.Update(clone)
}

// StartExclusive prevents overlapping workers that would contend for the same
// destructive resource. Keys are process-local because every queued/running
// task is marked interrupted before a new Runtime accepts work.
func (runtime *Runtime) StartExclusive(task *Task, runner Runner, keys ...string) error {
	return runtime.start(task, runner, keys)
}

func (runtime *Runtime) start(task *Task, runner Runner, keys []string) error {
	if task == nil || task.Status != StatusQueued || runner == nil {
		return ErrState
	}
	ctx, cancel := context.WithCancel(context.Background())
	runtime.mu.Lock()
	if _, exists := runtime.cancels[task.ID]; exists {
		runtime.mu.Unlock()
		cancel()
		return ErrState
	}
	for _, key := range keys {
		if key == "" {
			continue
		}
		if _, exists := runtime.keys[key]; exists {
			runtime.mu.Unlock()
			cancel()
			return ErrState
		}
	}
	runtime.cancels[task.ID] = cancel
	for _, key := range keys {
		if key != "" {
			runtime.keys[key] = task.ID
			runtime.taskKeys[task.ID] = append(runtime.taskKeys[task.ID], key)
		}
	}
	runtime.mu.Unlock()

	go runtime.run(ctx, task.Clone(), runner)
	return nil
}

func (runtime *Runtime) Cancel(userID uint, id string, admin bool) (*Task, error) {
	task, err := runtime.storage.Get(userID, id, admin)
	if err != nil {
		return nil, err
	}
	if !task.CanCancel() {
		return nil, ErrState
	}

	runtime.mu.Lock()
	cancel := runtime.cancels[id]
	runtime.mu.Unlock()
	if cancel != nil {
		cancel()
		return task, nil
	}

	// A queued task can be visible before its worker is attached. If no worker
	// exists, cancellation is persisted synchronously.
	task.Status = StatusCanceled
	task.FinishedAt = time.Now().UnixMilli()
	task.Error = "任务已取消"
	if err := runtime.storage.Update(task); err != nil {
		return nil, err
	}
	return task, nil
}

func (runtime *Runtime) run(ctx context.Context, task *Task, runner Runner) {
	defer runtime.release(task.ID)

	task.Status = StatusRunning
	task.StartedAt = time.Now().UnixMilli()
	task.FinishedAt = 0
	task.Error = ""
	if err := runtime.storage.Update(task); err != nil {
		return
	}

	var reportMu sync.Mutex
	lastPersistAt := time.Time{}
	lastPersistBytes := int64(0)
	report := func(progress Progress) error {
		reportMu.Lock()
		defer reportMu.Unlock()
		task.TotalItems = progress.TotalItems
		task.ProcessedItems = progress.ProcessedItems
		task.TotalBytes = progress.TotalBytes
		task.ProcessedBytes = progress.ProcessedBytes
		if len(progress.Checkpoint) > 0 {
			task.Result = append(json.RawMessage(nil), progress.Checkpoint...)
		}
		now := time.Now()
		force := len(progress.Checkpoint) > 0
		if !force && !lastPersistAt.IsZero() && now.Sub(lastPersistAt) < time.Second && progress.ProcessedBytes-lastPersistBytes < 8*1024*1024 {
			return nil
		}
		lastPersistAt = now
		lastPersistBytes = progress.ProcessedBytes
		return runtime.storage.Update(task)
	}

	result, err := runner(ctx, report)
	if len(result) > 0 {
		task.Result = append(json.RawMessage(nil), result...)
	}
	task.FinishedAt = time.Now().UnixMilli()
	switch {
	case errors.Is(ctx.Err(), context.Canceled), errors.Is(err, context.Canceled):
		task.Status = StatusCanceled
		task.Error = "任务已取消"
	case err != nil:
		task.Status = StatusFailed
		task.Error = err.Error()
	default:
		task.Status = StatusCompleted
		task.Error = ""
	}
	// A persisted terminal state is an API promise that the task can be
	// retried immediately. Release process-local slots before publishing it.
	runtime.release(task.ID)
	_ = runtime.storage.Update(task)
}

func (runtime *Runtime) release(taskID string) {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	delete(runtime.cancels, taskID)
	for _, key := range runtime.taskKeys[taskID] {
		if runtime.keys[key] == taskID {
			delete(runtime.keys, key)
		}
	}
	delete(runtime.taskKeys, taskID)
}
