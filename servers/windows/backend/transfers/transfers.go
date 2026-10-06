// Package transfers stores durable upload and download activity.
//
// Transfers deliberately live outside the background-task store: a transfer
// can be resumed or inspected independently from a task and a native browser
// download must not be forced through an in-memory Blob/task abstraction.
package transfers

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"sync"
	"time"
)

const MaxEntriesPerUser = 500

var (
	ErrNotExist = errors.New("transfer not found")
	ErrInvalid  = errors.New("invalid transfer")
)

type Kind string

const (
	KindUpload   Kind = "upload"
	KindDownload Kind = "download"
)

type Status string

const (
	StatusQueued      Status = "queued"
	StatusRunning     Status = "running"
	StatusCompleted   Status = "completed"
	StatusFailed      Status = "failed"
	StatusCanceled    Status = "canceled"
	StatusInterrupted Status = "interrupted"
)

// Item is the public durable representation. UserID is intentionally omitted
// from JSON; all reads are scoped by the authenticated user.
type Item struct {
	ID               string `json:"id" storm:"id"`
	UserID           uint   `json:"-" storm:"index"`
	Kind             Kind   `json:"kind" storm:"index"`
	Status           Status `json:"status" storm:"index"`
	Name             string `json:"name"`
	Target           string `json:"target"`
	BytesTotal       int64  `json:"bytesTotal,omitempty"`
	BytesTransferred int64  `json:"bytesTransferred"`
	Error            string `json:"error,omitempty"`
	CreatedAt        int64  `json:"createdAt" storm:"index"`
	StartedAt        int64  `json:"startedAt,omitempty"`
	FinishedAt       int64  `json:"finishedAt,omitempty"`
	BatchID          string `json:"batchId,omitempty"`
	BatchName        string `json:"batchName,omitempty"`
	BatchItems       int    `json:"batchItems,omitempty"`
	BatchBytes       int64  `json:"batchBytes,omitempty"`
	IsFolderUpload   bool   `json:"isFolderUpload,omitempty"`
}

func (item *Item) Clone() *Item {
	if item == nil {
		return nil
	}
	copy := *item
	return &copy
}

type StorageBackend interface {
	GetAll() ([]*Item, error)
	GetByID(id string) (*Item, error)
	Save(item *Item) error
	Update(item *Item) error
	Delete(id string) error
}

// RecentCursor is the stable keyset position used by transfer history.
// CreatedAt and ID together keep ordering deterministic when records share a
// millisecond timestamp.
type RecentCursor struct {
	CreatedAt int64
	ID        string
}

// RecentTransferBackend lets persistent stores answer a bounded,
// user-scoped query without loading the complete transfer table.
type RecentTransferBackend interface {
	ListRecent(userID uint, kind Kind, limit int, after *RecentCursor) ([]*Item, error)
	Count(userID uint, kind Kind) (int, error)
}

// FilteredRecentTransferBackend is the optional persistent implementation for
// the task-center status filter. The original RecentTransferBackend remains in
// place so lightweight integrations and older callers keep compiling.
type FilteredRecentTransferBackend interface {
	ListRecentFiltered(userID uint, kind Kind, statuses []Status, limit int, after *RecentCursor) ([]*Item, error)
	CountFiltered(userID uint, kind Kind, statuses []Status) (int, error)
}

type Storage struct {
	back          StorageBackend
	mu            sync.Mutex
	lastCreatedAt int64
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

func (storage *Storage) New(userID uint, kind Kind, name, target string, bytesTotal int64) (*Item, error) {
	if kind != KindUpload && kind != KindDownload {
		return nil, ErrInvalid
	}
	if bytesTotal < 0 {
		bytesTotal = 0
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()

	createdAt := time.Now().UnixMilli()
	if createdAt <= storage.lastCreatedAt {
		createdAt = storage.lastCreatedAt + 1
	}
	storage.lastCreatedAt = createdAt
	id, err := generateID()
	if err != nil {
		return nil, err
	}
	item := &Item{
		ID: id, UserID: userID, Kind: kind, Status: StatusQueued,
		Name: name, Target: target, BytesTotal: bytesTotal, CreatedAt: createdAt,
	}
	if err := storage.back.Save(item); err != nil {
		return nil, err
	}
	if err := storage.pruneLocked(userID, kind); err != nil {
		return nil, err
	}
	return item.Clone(), nil
}

// Ensure creates or returns a transfer with a caller-provided id. It is used
// by raw downloads so the native browser request can start immediately while
// the metadata POST is still in flight. The user scope prevents id probing.
func (storage *Storage) Ensure(userID uint, id string, kind Kind, name, target string, bytesTotal int64) (*Item, error) {
	if id == "" {
		return storage.New(userID, kind, name, target, bytesTotal)
	}
	if existing, err := storage.back.GetByID(id); err == nil {
		if existing.UserID != userID || existing.Kind != kind {
			return nil, ErrNotExist
		}
		return existing.Clone(), nil
	} else if !errors.Is(err, ErrNotExist) {
		return nil, err
	}
	if kind != KindUpload && kind != KindDownload {
		return nil, ErrInvalid
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	// The first lookup intentionally happens outside the mutex for the common
	// existing-record path. Recheck after acquiring it so two concurrent
	// browser requests (for example the native download and its telemetry POST)
	// cannot both pass the miss and attempt to insert the same caller id.
	if existing, err := storage.back.GetByID(id); err == nil {
		if existing.UserID != userID || existing.Kind != kind {
			return nil, ErrNotExist
		}
		return existing.Clone(), nil
	} else if !errors.Is(err, ErrNotExist) {
		return nil, err
	}
	createdAt := time.Now().UnixMilli()
	if createdAt <= storage.lastCreatedAt {
		createdAt = storage.lastCreatedAt + 1
	}
	storage.lastCreatedAt = createdAt
	item := &Item{ID: id, UserID: userID, Kind: kind, Status: StatusQueued, Name: name, Target: target, BytesTotal: maxZero(bytesTotal), CreatedAt: createdAt}
	if err := storage.back.Save(item); err != nil {
		return nil, err
	}
	if err := storage.pruneLocked(userID, kind); err != nil {
		return nil, err
	}
	return item.Clone(), nil
}

func (storage *Storage) Get(userID uint, id string, admin bool) (*Item, error) {
	item, err := storage.back.GetByID(id)
	if err != nil {
		if errors.Is(err, ErrNotExist) {
			return nil, ErrNotExist
		}
		return nil, err
	}
	if !admin && item.UserID != userID {
		return nil, ErrNotExist
	}
	return item.Clone(), nil
}

func (storage *Storage) List(userID uint, kind Kind, limit int) ([]*Item, error) {
	all, err := storage.back.GetAll()
	if err != nil {
		return nil, err
	}
	filtered := make([]*Item, 0, len(all))
	for _, item := range all {
		if item.UserID == userID && (kind == "" || item.Kind == kind) {
			filtered = append(filtered, item)
		}
	}
	sortItems(filtered)
	if limit <= 0 || limit > MaxEntriesPerUser {
		limit = MaxEntriesPerUser
	}
	if len(filtered) > limit {
		filtered = filtered[:limit]
	}
	result := make([]*Item, len(filtered))
	for index, item := range filtered {
		result[index] = item.Clone()
	}
	return result, nil
}

// ListPage returns a bounded newest-first page. Persistent backends use a
// keyset query; the in-memory fallback keeps the same cursor semantics for
// tests and lightweight integrations.
func (storage *Storage) ListPage(userID uint, kind Kind, limit int, after *RecentCursor) ([]*Item, error) {
	return storage.ListPageFiltered(userID, kind, nil, limit, after)
}

// ListPageFiltered returns a bounded page while applying optional status
// filters in the persistent query. It is used by the upload/download history
// tabs; callers that do not need filtering can continue using ListPage.
func (storage *Storage) ListPageFiltered(userID uint, kind Kind, statuses []Status, limit int, after *RecentCursor) ([]*Item, error) {
	if limit < 1 {
		return []*Item{}, nil
	}
	if backend, ok := storage.back.(FilteredRecentTransferBackend); ok {
		return backend.ListRecentFiltered(userID, kind, statuses, limit, after)
	}
	if len(statuses) == 0 {
		if backend, ok := storage.back.(RecentTransferBackend); ok {
			return backend.ListRecent(userID, kind, limit, after)
		}
	}
	all, err := storage.List(userID, kind, MaxEntriesPerUser)
	if err != nil {
		return nil, err
	}
	result := make([]*Item, 0, limit)
	for _, item := range all {
		if len(statuses) > 0 && !containsStatus(statuses, item.Status) {
			continue
		}
		if after != nil && (item.CreatedAt > after.CreatedAt || item.CreatedAt == after.CreatedAt && item.ID >= after.ID) {
			continue
		}
		result = append(result, item)
		if len(result) == limit {
			break
		}
	}
	return result, nil
}

// Count returns the number of records visible to a user for the requested
// transfer kind. Persistent backends answer this without loading records.
func (storage *Storage) Count(userID uint, kind Kind) (int, error) {
	return storage.CountFiltered(userID, kind, nil)
}

func (storage *Storage) CountFiltered(userID uint, kind Kind, statuses []Status) (int, error) {
	if backend, ok := storage.back.(FilteredRecentTransferBackend); ok {
		return backend.CountFiltered(userID, kind, statuses)
	}
	if backend, ok := storage.back.(RecentTransferBackend); ok {
		count, err := backend.Count(userID, kind)
		if err != nil || len(statuses) == 0 {
			return count, err
		}
		// Older persistent backends do not understand status filters. Fall
		// through to the portable scan below so the result remains correct.
	}
	all, err := storage.back.GetAll()
	if err != nil {
		return 0, err
	}
	count := 0
	for _, item := range all {
		if item.UserID == userID && (kind == "" || item.Kind == kind) &&
			(len(statuses) == 0 || containsStatus(statuses, item.Status)) {
			count++
		}
	}
	return count, nil
}

func containsStatus(statuses []Status, status Status) bool {
	for _, candidate := range statuses {
		if candidate == status {
			return true
		}
	}
	return false
}

func (storage *Storage) Update(item *Item) error {
	if item == nil || item.ID == "" {
		return ErrInvalid
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	if err := storage.back.Update(item); err != nil {
		return err
	}
	return nil
}

func (storage *Storage) Progress(id string, userID uint, transferred int64) (*Item, error) {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	item, err := storage.back.GetByID(id)
	if err != nil {
		return nil, err
	}
	if item.UserID != userID {
		return nil, ErrNotExist
	}
	if transferred < item.BytesTransferred {
		transferred = item.BytesTransferred
	}
	if item.BytesTotal > 0 && transferred > item.BytesTotal {
		transferred = item.BytesTotal
	}
	item.BytesTransferred = transferred
	if item.Status == StatusQueued {
		item.Status = StatusRunning
		item.StartedAt = time.Now().UnixMilli()
	}
	if err := storage.back.Update(item); err != nil {
		return nil, err
	}
	return item.Clone(), nil
}

func (storage *Storage) SetStatus(id string, userID uint, status Status, message string) (*Item, error) {
	if !validStatus(status) {
		return nil, ErrInvalid
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	item, err := storage.back.GetByID(id)
	if err != nil {
		return nil, err
	}
	if item.UserID != userID {
		return nil, ErrNotExist
	}
	item.Status = status
	item.Error = message
	now := time.Now().UnixMilli()
	if status == StatusRunning && item.StartedAt == 0 {
		item.StartedAt = now
	}
	if status == StatusCompleted && item.BytesTotal > 0 {
		item.BytesTransferred = item.BytesTotal
	}
	if status == StatusCompleted || status == StatusFailed || status == StatusCanceled || status == StatusInterrupted {
		item.FinishedAt = now
	}
	if err := storage.back.Update(item); err != nil {
		return nil, err
	}
	return item.Clone(), nil
}

func (storage *Storage) Delete(userID uint, id string, admin bool) error {
	storage.mu.Lock()
	defer storage.mu.Unlock()
	item, err := storage.back.GetByID(id)
	if err != nil {
		return err
	}
	if !admin && item.UserID != userID {
		return ErrNotExist
	}
	return storage.back.Delete(id)
}

// DeleteAll removes only the authenticated user's records for one kind. The
// kind is intentionally explicit so the UI cannot accidentally clear uploads
// and downloads together; administrator access does not widen this action.
func (storage *Storage) DeleteAll(userID uint, kind Kind, _ bool) (int, error) {
	if kind != KindUpload && kind != KindDownload {
		return 0, ErrInvalid
	}
	storage.mu.Lock()
	defer storage.mu.Unlock()
	all, err := storage.back.GetAll()
	if err != nil {
		return 0, err
	}
	removed := 0
	for _, item := range all {
		if item.Kind != kind || item.UserID != userID {
			continue
		}
		if err := storage.back.Delete(item.ID); err != nil {
			return removed, err
		}
		removed++
	}
	return removed, nil
}

func (storage *Storage) pruneLocked(userID uint, kind Kind) error {
	all, err := storage.back.GetAll()
	if err != nil {
		return err
	}
	items := make([]*Item, 0, len(all))
	for _, item := range all {
		if item.UserID == userID && item.Kind == kind {
			items = append(items, item)
		}
	}
	sortItems(items)
	if len(items) <= MaxEntriesPerUser {
		return nil
	}
	for _, item := range items[MaxEntriesPerUser:] {
		if err := storage.back.Delete(item.ID); err != nil {
			return err
		}
	}
	return nil
}

func sortItems(items []*Item) {
	sort.SliceStable(items, func(left, right int) bool {
		if items[left].CreatedAt == items[right].CreatedAt {
			return items[left].ID > items[right].ID
		}
		return items[left].CreatedAt > items[right].CreatedAt
	})
}

func validStatus(status Status) bool {
	switch status {
	case StatusQueued, StatusRunning, StatusCompleted, StatusFailed, StatusCanceled, StatusInterrupted:
		return true
	default:
		return false
	}
}

func maxZero(value int64) int64 {
	if value < 0 {
		return 0
	}
	return value
}

func generateID() (string, error) {
	random := make([]byte, 10)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return time.Now().UTC().Format("20060102T150405.000000000") + "-" + hex.EncodeToString(random), nil
}
