package trash

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
)

var (
	ErrNotExist  = errors.New("trash item not found")
	ErrForbidden = errors.New("trash item access denied")
)

type SizeState string

const (
	SizeUnknown     SizeState = "unknown"
	SizeCalculating SizeState = "calculating"
	SizeAccurate    SizeState = "accurate"
	SizeIncomplete  SizeState = "incomplete"
	SizeFailed      SizeState = "failed"
)

type Status string

const (
	StatusPending   Status = "pending"
	StatusAvailable Status = "available"
	StatusRestoring Status = "restoring"
	StatusFailed    Status = "failed"
)

// Item is the durable record for one file or directory moved into the
// application-managed recycle bin. HTTP handlers must expose PublicItem so
// hidden storage paths and staged metadata never leave the backend.
type Item struct {
	SizeState         SizeState            `json:"sizeState"`
	SizeTaskID        string               `json:"sizeTaskId,omitempty"`
	ID                string               `json:"id" storm:"id"`
	UserID            uint                 `json:"userId" storm:"index"`
	OwnerName         string               `json:"ownerName"`
	OriginalPath      string               `json:"originalPath" storm:"index"`
	StoredPath        string               `json:"-" storm:"unique"`
	Name              string               `json:"name"`
	IsDir             bool                 `json:"isDir"`
	Size              int64                `json:"size"`
	DeletedAt         int64                `json:"deletedAt" storm:"index"`
	Status            Status               `json:"status" storm:"index"`
	LastError         string               `json:"error,omitempty"`
	FavoriteSnapshots []favorites.Favorite `json:"-"`
	TagSnapshots      []tags.Tag           `json:"-"`
}

type PublicItem struct {
	SizeState    SizeState `json:"sizeState"`
	SizeTaskID   string    `json:"sizeTaskId,omitempty"`
	ID           string    `json:"id"`
	UserID       uint      `json:"userId"`
	OwnerName    string    `json:"ownerName"`
	OriginalPath string    `json:"originalPath"`
	Name         string    `json:"name"`
	IsDir        bool      `json:"isDir"`
	Size         int64     `json:"size"`
	DeletedAt    int64     `json:"deletedAt"`
	Status       Status    `json:"status"`
	LastError    string    `json:"error,omitempty"`
}

func (item *Item) Public() PublicItem {
	state := item.SizeState
	if state == "" {
		if item.IsDir {
			state = SizeUnknown
		} else {
			state = SizeAccurate
		}
	}
	return PublicItem{
		SizeState: state, SizeTaskID: item.SizeTaskID,
		ID:           item.ID,
		UserID:       item.UserID,
		OwnerName:    item.OwnerName,
		OriginalPath: item.OriginalPath,
		Name:         item.Name,
		IsDir:        item.IsDir,
		Size:         item.Size,
		DeletedAt:    item.DeletedAt,
		Status:       item.Status,
		LastError:    item.LastError,
	}
}

func (item *Item) Clone() *Item {
	if item == nil {
		return nil
	}
	clone := *item
	clone.FavoriteSnapshots = append([]favorites.Favorite(nil), item.FavoriteSnapshots...)
	clone.TagSnapshots = make([]tags.Tag, len(item.TagSnapshots))
	for index, tag := range item.TagSnapshots {
		clone.TagSnapshots[index] = tag
		clone.TagSnapshots[index].Paths = append([]string(nil), tag.Paths...)
	}
	return &clone
}

type StorageBackend interface {
	GetAll() ([]*Item, error)
	GetByID(id string) (*Item, error)
	Save(item *Item) error
	Update(item *Item) error
	Delete(id string) error
}

type Storage struct {
	sizeSlots chan struct{}
	back      StorageBackend
	mu        sync.Mutex
	locks     map[string]*itemLock
	sizing    map[string]context.CancelFunc
}

func NewStorage(back StorageBackend) *Storage {
	return &Storage{sizeSlots: make(chan struct{}, 1), back: back, locks: make(map[string]*itemLock), sizing: make(map[string]context.CancelFunc)}
}

func NewItem(userID uint, ownerName, originalPath, name string, isDir bool, size int64) (*Item, error) {
	id, err := generateID()
	if err != nil {
		return nil, err
	}
	state := SizeAccurate
	if isDir {
		size = 0
		state = SizeUnknown
	}
	return &Item{
		SizeState:    state,
		ID:           id,
		UserID:       userID,
		OwnerName:    ownerName,
		OriginalPath: originalPath,
		Name:         name,
		IsDir:        isDir,
		Size:         size,
		DeletedAt:    time.Now().UnixMilli(),
		Status:       StatusPending,
	}, nil
}

func (s *Storage) Save(item *Item) error {
	return s.back.Save(item.Clone())
}

func (s *Storage) Update(item *Item) error {
	return s.back.Update(item.Clone())
}

func (s *Storage) Delete(userID uint, id string, admin bool) error {
	if _, err := s.Get(userID, id, admin); err != nil {
		return err
	}
	return s.back.Delete(id)
}

func (s *Storage) Get(userID uint, id string, admin bool) (*Item, error) {
	item, err := s.back.GetByID(id)
	if err != nil {
		return nil, err
	}
	if !admin && item.UserID != userID {
		return nil, ErrForbidden
	}
	return item.Clone(), nil
}

func (s *Storage) List(userID uint, admin bool) ([]*Item, error) {
	all, err := s.back.GetAll()
	if err != nil {
		return nil, err
	}
	items := make([]*Item, 0, len(all))
	for _, item := range all {
		if !admin && item.UserID != userID {
			continue
		}
		items = append(items, item.Clone())
	}
	sort.SliceStable(items, func(left, right int) bool {
		if items[left].DeletedAt == items[right].DeletedAt {
			return items[left].ID > items[right].ID
		}
		return items[left].DeletedAt > items[right].DeletedAt
	})
	return items, nil
}

func generateID() (string, error) {
	random := make([]byte, 8)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	return time.Now().UTC().Format("20060102T150405.000000000") + "-" + hex.EncodeToString(random), nil
}
