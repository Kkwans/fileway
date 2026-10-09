// Package uploads owns durable TUS sessions independently of removable task
// history. Filesystem verification and HTTP remain in their existing packages.
package uploads

import (
	"errors"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
)

const Retention = 7 * 24 * time.Hour

var (
	ErrNotExist = errors.New("upload session not found")
	ErrConflict = errors.New("upload session changed")
	ErrExpired  = errors.New("原上传已超过保留期")
)

type State string

const (
	Active    State = "active"
	Completed State = "completed"
	Canceled  State = "canceled"
	Expired   State = "expired"
	Changed   State = "changed"
)

type Session struct {
	ID             string
	UserID         uint
	WirePath       string
	PartWire       string
	RootKey        string
	Overwrite      bool
	TargetIdentity *files.Identity
	TargetSize     int64
	TargetModified int64
	Length         int64
	Offset         int64
	Modified       int64
	Writing        bool
	ReservedTo     int64
	Identity       *files.Identity
	State          State
	CreatedAt      int64
	UpdatedAt      int64
	ExpiresAt      int64
}

func (s *Session) Clone() *Session {
	if s == nil {
		return nil
	}
	copy := *s
	if s.Identity != nil {
		identity := *s.Identity
		copy.Identity = &identity
	}
	if s.TargetIdentity != nil {
		identity := *s.TargetIdentity
		copy.TargetIdentity = &identity
	}
	return &copy
}

type Backend interface {
	Get(string) (*Session, error)
	Save(*Session) error
	All() ([]*Session, error)
}

type lock struct {
	mutex sync.Mutex
	users int
}
type Storage struct {
	backend Backend
	mutex   sync.Mutex
	locks   map[string]*lock
}

func NewStorage(backend Backend) *Storage {
	return &Storage{backend: backend, locks: make(map[string]*lock)}
}

// Lock serializes a physical target across owners/scopes within one server.
// Reference counts avoid retaining one mutex forever per historical filename.
func (s *Storage) Lock(key string) func() {
	s.mutex.Lock()
	entry := s.locks[key]
	if entry == nil {
		entry = &lock{}
		s.locks[key] = entry
	}
	entry.users++
	s.mutex.Unlock()
	entry.mutex.Lock()
	return func() {
		entry.mutex.Unlock()
		s.mutex.Lock()
		entry.users--
		if entry.users == 0 {
			delete(s.locks, key)
		}
		s.mutex.Unlock()
	}
}

func (s *Storage) Get(id string, user uint, wire, root string) (*Session, error) {
	row, err := s.backend.Get(id)
	if err != nil {
		return nil, err
	}
	if row.UserID != user {
		return nil, ErrNotExist
	}
	if row.WirePath != wire || row.RootKey != root {
		return nil, ErrConflict
	}
	return row.Clone(), nil
}

func (s *Storage) Find(user uint, wire, root string) (*Session, error) {
	rows, err := s.backend.All()
	if err != nil {
		return nil, err
	}
	var latest *Session
	for _, row := range rows {
		if row.UserID == user && row.WirePath == wire && row.RootKey == root && (row.State == Active || row.State == Completed) && (latest == nil || row.UpdatedAt > latest.UpdatedAt) {
			latest = row
		}
	}
	if latest == nil {
		return nil, ErrNotExist
	}
	return latest.Clone(), nil
}

// A private pending part still reserves its final target. Another upload may
// not spend hours transferring bytes only to collide at publication.
func (s *Storage) TargetBusy(root string, now time.Time) (bool, error) {
	rows, err := s.backend.All()
	if err != nil {
		return false, err
	}
	for _, row := range rows {
		if row.RootKey == root && row.State == Active && row.ExpiresAt > now.UnixMilli() {
			return true, nil
		}
	}
	return false, nil
}

func (s *Storage) Save(row *Session) error {
	if row.ID == "" || row.WirePath == "" || row.RootKey == "" || row.Length < 0 || row.Offset < 0 || row.Offset > row.Length {
		return ErrConflict
	}
	return s.backend.Save(row.Clone())
}

func (s *Storage) Create(row *Session) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if _, err := s.backend.Get(row.ID); err == nil {
		return ErrConflict
	} else if !errors.Is(err, ErrNotExist) {
		return err
	}
	return s.Save(row)
}

func (s *Storage) Due(now time.Time) ([]*Session, error) {
	rows, err := s.backend.All()
	if err != nil {
		return nil, err
	}
	result := make([]*Session, 0)
	for _, row := range rows {
		if row.State == Active && row.ExpiresAt <= now.UnixMilli() {
			result = append(result, row.Clone())
		}
	}
	return result, nil
}

// Touch occurs after real filesystem confirmation, including a partial write
// whose HTTP acknowledgement was lost. It does not infer completion from IO alone.
func Touch(row *Session, now time.Time) {
	row.UpdatedAt = now.UnixMilli()
	row.ExpiresAt = now.Add(Retention).UnixMilli()
}
