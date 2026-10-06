package favorites

import (
	"errors"
	"fmt"
	"sync/atomic"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

var (
	ErrExist      = errors.New("favorite already exists")
	ErrNotExist   = errors.New("favorite not found")
	ErrGroupExist = errors.New("group already exists")
)

// FavoriteGroup represents a virtual directory for organizing favorites.
type FavoriteGroup struct {
	ID     string `json:"id" storm:"id"`
	UserID uint   `json:"-" storm:"index"`
	Name   string `json:"name"`
	Order  int    `json:"order"`
	Color  string `json:"color,omitempty"`
}

// Favorite represents a bookmarked file/folder path.
type Favorite struct {
	ID      string `json:"id" storm:"id"`
	UserID  uint   `json:"-" storm:"index"`
	Path    string `json:"path" storm:"index"`
	Name    string `json:"name"`
	GroupID string `json:"groupId,omitempty"`
	AddedAt int64  `json:"addedAt"`
	Order   int    `json:"order"`
}

// GroupStorageBackend is the interface for favorite group storage.
type GroupStorageBackend interface {
	GetAllGroups(userID uint) ([]*FavoriteGroup, error)
	GetGroupByID(userID uint, id string) (*FavoriteGroup, error)
	SaveGroup(group *FavoriteGroup) error
	UpdateGroup(group *FavoriteGroup) error
	DeleteGroup(id string) error
	ClaimLegacy(userID uint) error
}

// StorageBackend is the interface to implement for a favorites storage.
type StorageBackend interface {
	GetAll(userID uint) ([]*Favorite, error)
	GetAllForPathMutation() ([]*Favorite, error)
	GetByID(userID uint, id string) (*Favorite, error)
	GetByPath(userID uint, path string) (*Favorite, error)
	Save(fav *Favorite) error
	Update(fav *Favorite) error
	UpdatePath(id string, path string) error
	UpdateGroupID(id string, groupID string) error
	Delete(id string) error
	DeleteByPath(path string) error
	GroupStorageBackend
}

// AtomicGroupDeletionBackend lets a persistent backend perform the group
// reassignment and group removal in one transaction. The fallback in
// Storage.DeleteGroup keeps lightweight test/backfill backends compatible.
type AtomicGroupDeletionBackend interface {
	DeleteGroupAndUngroup(userID uint, id string) error
}

// PathMutation records the exact metadata rows changed by a filesystem path
// operation so a later cross-system failure can restore them without touching
// unrelated favorites that already existed at the destination.
type PathMutation struct {
	updated []Favorite
	deleted []Favorite
}

// DeletedSnapshot returns an independent copy of favorites removed by a path
// mutation. Recycle-bin records persist this snapshot until restore or
// permanent deletion.
func (m *PathMutation) DeletedSnapshot() []Favorite {
	if m == nil {
		return nil
	}
	return append([]Favorite(nil), m.deleted...)
}

// Storage is the high-level storage for favorites.
type Storage struct {
	back StorageBackend
}

// NewStorage creates a favorites storage from a backend.
func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

// GetAll returns all favorites.
func (s *Storage) GetAll(userID uint) ([]*Favorite, error) {
	return s.back.GetAll(userID)
}

// ClaimLegacy assigns records created before per-user ownership to the first
// administrator that opens the corresponding workspace.
func (s *Storage) ClaimLegacy(userID uint) error {
	return s.back.ClaimLegacy(userID)
}

// GetByID returns a favorite by ID.
func (s *Storage) GetByID(userID uint, id string) (*Favorite, error) {
	return s.back.GetByID(userID, id)
}

// Add creates a new favorite. Returns error if path already exists.
func (s *Storage) Add(userID uint, path, name string, currentCount int) (*Favorite, error) {
	return s.AddToGroup(userID, path, name, "", currentCount)
}

// AddToGroup creates a new favorite in a specific group.
func (s *Storage) AddToGroup(userID uint, path, name, groupID string, currentCount int) (*Favorite, error) {
	_, err := s.back.GetByPath(userID, path)
	if err == nil {
		return nil, ErrExist
	}
	if !errors.Is(err, ErrNotExist) {
		return nil, err
	}

	fav := &Favorite{
		ID:      GenerateID(),
		UserID:  userID,
		Path:    path,
		Name:    name,
		GroupID: groupID,
		AddedAt: time.Now().UnixMilli(),
		Order:   currentCount,
	}

	if err := s.back.Save(fav); err != nil {
		return nil, err
	}
	return fav, nil
}

// UpdateFields updates name, order, and/or groupID of a favorite.
func (s *Storage) UpdateFields(userID uint, id string, name *string, order *int) (*Favorite, error) {
	return s.UpdateFieldsEx(userID, id, name, order, nil)
}

// UpdateFieldsEx updates name, order, and/or groupID of a favorite.
func (s *Storage) UpdateFieldsEx(userID uint, id string, name *string, order *int, groupID *string) (*Favorite, error) {
	fav, err := s.back.GetByID(userID, id)
	if err != nil {
		return nil, err
	}

	if name != nil {
		fav.Name = *name
	}
	if order != nil {
		fav.Order = *order
	}
	if groupID != nil {
		fav.GroupID = *groupID
	}

	if err := s.back.Update(fav); err != nil {
		return nil, err
	}
	// Storm 的 Update 会忽略零值字段，因此移出分组时必须显式写入空字符串。
	if groupID != nil {
		if err := s.back.UpdateGroupID(fav.ID, *groupID); err != nil {
			return nil, err
		}
	}
	return fav, nil
}

// Delete removes a favorite by ID.
func (s *Storage) Delete(userID uint, id string) error {
	if _, err := s.back.GetByID(userID, id); err != nil {
		return err
	}
	return s.back.Delete(id)
}

// DeleteByPath removes a favorite by path.
func (s *Storage) DeleteByPath(userID uint, path string) error {
	fav, err := s.back.GetByPath(userID, path)
	if err != nil {
		return err
	}
	return s.back.Delete(fav.ID)
}

// RewritePathPrefix updates matching favorites for every user. The operation
// is internally compensating: a partial backend failure restores earlier rows.
func (s *Storage) RewritePathPrefix(from, to string) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, favorite := range all {
		rewritten, matched := pathmeta.Rewrite(favorite.Path, from, to)
		if !matched || rewritten == favorite.Path {
			continue
		}

		original := *favorite
		if err := s.back.UpdatePath(favorite.ID, rewritten); err != nil {
			return nil, errors.Join(err, s.RestorePathMutation(mutation))
		}
		mutation.updated = append(mutation.updated, original)
	}
	return mutation, nil
}

// RemovePathPrefix deletes matching favorites for every user and returns a
// restorable mutation for coordination with the filesystem operation.
func (s *Storage) RemovePathPrefix(prefix string) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, favorite := range all {
		if !pathmeta.Contains(favorite.Path, prefix) {
			continue
		}

		original := *favorite
		if err := s.back.Delete(favorite.ID); err != nil {
			return nil, errors.Join(err, s.RestorePathMutation(mutation))
		}
		mutation.deleted = append(mutation.deleted, original)
	}
	return mutation, nil
}

// RestorePathMutation restores only the rows captured by a prior mutation.
func (s *Storage) RestorePathMutation(mutation *PathMutation) error {
	if mutation == nil {
		return nil
	}

	var restoreErr error
	for _, favorite := range mutation.updated {
		restoreErr = errors.Join(restoreErr, s.back.UpdatePath(favorite.ID, favorite.Path))
	}
	restored := make([]Favorite, 0, len(mutation.deleted))
	for _, favorite := range mutation.deleted {
		copy := favorite
		if err := s.back.Save(&copy); err != nil {
			for _, saved := range restored {
				restoreErr = errors.Join(restoreErr, s.back.Delete(saved.ID))
			}
			return errors.Join(restoreErr, err)
		}
		restored = append(restored, favorite)
	}
	return restoreErr
}

// RestoreDeletedSnapshot restores exact favorite rows previously returned by
// DeletedSnapshot without touching newer favorites at unrelated paths.
func (s *Storage) RestoreDeletedSnapshot(snapshot []Favorite) error {
	return s.RestorePathMutation(&PathMutation{
		deleted: append([]Favorite(nil), snapshot...),
	})
}

// RestoreStagedSnapshot restores recycle-bin favorites without duplicating a
// favorite the user deliberately created for the same destination while the
// resource was trashed. The returned slice contains only rows created by this
// call so a later cross-system failure can compensate precisely.
func (s *Storage) RestoreStagedSnapshot(snapshot []Favorite) ([]Favorite, error) {
	restored := make([]Favorite, 0, len(snapshot))
	for _, favorite := range snapshot {
		_, err := s.back.GetByPath(favorite.UserID, favorite.Path)
		if err == nil {
			continue
		}
		if !errors.Is(err, ErrNotExist) {
			return nil, errors.Join(err, s.deleteSnapshot(restored))
		}
		copy := favorite
		if err := s.back.Save(&copy); err != nil {
			return nil, errors.Join(err, s.deleteSnapshot(restored))
		}
		restored = append(restored, favorite)
	}
	return restored, nil
}

func (s *Storage) deleteSnapshot(snapshot []Favorite) error {
	var deleteErr error
	for _, favorite := range snapshot {
		deleteErr = errors.Join(deleteErr, s.back.Delete(favorite.ID))
	}
	return deleteErr
}

// Reorder replaces the entire order of favorites.
func (s *Storage) Reorder(userID uint, ids []string) error {
	for i, id := range ids {
		fav, err := s.back.GetByID(userID, id)
		if err != nil {
			return err
		}
		fav.Order = i
		if err := s.back.Update(fav); err != nil {
			return err
		}
	}
	return nil
}

// --- Group methods ---

// GetAllGroups returns all favorite groups.
func (s *Storage) GetAllGroups(userID uint) ([]*FavoriteGroup, error) {
	return s.back.GetAllGroups(userID)
}

// AddGroup creates a new favorite group.
func (s *Storage) AddGroup(userID uint, name, color string, currentCount int) (*FavoriteGroup, error) {
	group := &FavoriteGroup{
		ID:     GenerateID(),
		UserID: userID,
		Name:   name,
		Color:  color,
		Order:  currentCount,
	}
	if err := s.back.SaveGroup(group); err != nil {
		return nil, err
	}
	return group, nil
}

// UpdateGroupFields updates name/color of a group.
func (s *Storage) UpdateGroupFields(userID uint, id string, name *string, color *string) (*FavoriteGroup, error) {
	group, err := s.back.GetGroupByID(userID, id)
	if err != nil {
		return nil, err
	}
	if name != nil {
		group.Name = *name
	}
	if color != nil {
		group.Color = *color
	}
	if err := s.back.UpdateGroup(group); err != nil {
		return nil, err
	}
	return group, nil
}

// DeleteGroup moves the group's favorites to the ungrouped bucket and removes
// the group as one logical operation. Persistent backends may provide a real
// transaction through AtomicGroupDeletionBackend; the fallback compensates
// partial updates before returning an error.
func (s *Storage) DeleteGroup(userID uint, id string) error {
	if atomic, ok := s.back.(AtomicGroupDeletionBackend); ok {
		return atomic.DeleteGroupAndUngroup(userID, id)
	}

	if _, err := s.back.GetGroupByID(userID, id); err != nil {
		return err
	}
	allFavs, err := s.back.GetAll(userID)
	if err != nil {
		return err
	}
	updated := make([]*Favorite, 0)
	for _, fav := range allFavs {
		if fav.GroupID != id {
			continue
		}
		if err := s.back.UpdateGroupID(fav.ID, ""); err != nil {
			for _, previous := range updated {
				_ = s.back.UpdateGroupID(previous.ID, id)
			}
			return err
		}
		updated = append(updated, fav)
	}
	if err := s.back.DeleteGroup(id); err != nil {
		for _, previous := range updated {
			_ = s.back.UpdateGroupID(previous.ID, id)
		}
		return err
	}
	return nil
}

// ReorderGroups replaces the entire order of groups.
func (s *Storage) ReorderGroups(userID uint, ids []string) error {
	for i, id := range ids {
		group, err := s.back.GetGroupByID(userID, id)
		if err != nil {
			return err
		}
		group.Order = i
		if err := s.back.UpdateGroup(group); err != nil {
			return err
		}
	}
	return nil
}

// GenerateID creates a unique ID based on timestamp + counter.
var idCounter uint64

func GenerateID() string {
	c := atomic.AddUint64(&idCounter, 1)
	return time.Now().Format("20060102150405.000") + "-" + fmt.Sprintf("%06x", c&0xFFFFFF)
}
