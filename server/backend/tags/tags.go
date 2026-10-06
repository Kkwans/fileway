package tags

import (
	"errors"
	"fmt"
	"sync/atomic"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

var (
	ErrNotExist = errors.New("tag not found")
)

// Tag represents a label that can be attached to file/folder paths.
type Tag struct {
	ID        string   `json:"id" storm:"id"`
	UserID    uint     `json:"-" storm:"index"`
	Name      string   `json:"name" storm:"index"`
	Color     string   `json:"color"`
	Paths     []string `json:"paths"`
	CreatedAt int64    `json:"createdAt"`
}

// StorageBackend is the interface to implement for a tags storage.
type StorageBackend interface {
	GetAll(userID uint) ([]*Tag, error)
	GetAllForPathMutation() ([]*Tag, error)
	GetByID(userID uint, id string) (*Tag, error)
	Save(tag *Tag) error
	Update(tag *Tag) error
	UpdatePaths(id string, paths []string) error
	Delete(id string) error
	ClaimLegacy(userID uint) error
}

// PathMutation captures the complete path lists changed by a filesystem path
// operation so cross-system compensation restores exact prior state.
type PathMutation struct {
	updated []Tag
}

// UpdatedSnapshot returns a deep copy of tags changed by a path removal. The
// recycle bin persists these complete prior path lists for exact restoration.
func (m *PathMutation) UpdatedSnapshot() []Tag {
	if m == nil {
		return nil
	}
	snapshot := make([]Tag, len(m.updated))
	for index, tag := range m.updated {
		snapshot[index] = cloneTag(tag)
	}
	return snapshot
}

// Storage is the high-level storage for tags.
type Storage struct {
	back StorageBackend
}

// NewStorage creates a tags storage from a backend.
func NewStorage(back StorageBackend) *Storage {
	return &Storage{back: back}
}

// GetAll returns all tags.
func (s *Storage) GetAll(userID uint) ([]*Tag, error) {
	return s.back.GetAll(userID)
}

// ClaimLegacy assigns records created before per-user ownership to the first
// administrator that opens the corresponding workspace.
func (s *Storage) ClaimLegacy(userID uint) error {
	return s.back.ClaimLegacy(userID)
}

// GetByID returns a tag by ID.
func (s *Storage) GetByID(userID uint, id string) (*Tag, error) {
	return s.back.GetByID(userID, id)
}

// Create creates a new tag.
func (s *Storage) Create(userID uint, name, color string) (*Tag, error) {
	tag := &Tag{
		ID:        generateID(),
		UserID:    userID,
		Name:      name,
		Color:     color,
		Paths:     []string{},
		CreatedAt: time.Now().UnixMilli(),
	}
	if err := s.back.Save(tag); err != nil {
		return nil, err
	}
	return tag, nil
}

// UpdateFields updates name and/or color of a tag.
func (s *Storage) UpdateFields(userID uint, id string, name *string, color *string) (*Tag, error) {
	tag, err := s.back.GetByID(userID, id)
	if err != nil {
		return nil, err
	}

	if name != nil {
		tag.Name = *name
	}
	if color != nil {
		tag.Color = *color
	}

	if err := s.back.Update(tag); err != nil {
		return nil, err
	}
	return tag, nil
}

// Delete removes a tag by ID.

func (s *Storage) Delete(userID uint, id string) error {
	if _, err := s.back.GetByID(userID, id); err != nil {
		return err
	}
	return s.back.Delete(id)
}

// AddPath adds a path to a tag (no-op if already present).
func (s *Storage) AddPath(userID uint, id, path string) (*Tag, error) {
	tag, err := s.back.GetByID(userID, id)
	if err != nil {
		return nil, err
	}

	for _, p := range tag.Paths {
		if p == path {
			return tag, nil // already exists
		}
	}

	tag.Paths = append(tag.Paths, path)
	if err := s.back.Update(tag); err != nil {
		return nil, err
	}
	return tag, nil
}

// RemovePath removes a path from a tag.
func (s *Storage) RemovePath(userID uint, id, path string) (*Tag, error) {
	tag, err := s.back.GetByID(userID, id)
	if err != nil {
		return nil, err
	}

	newPaths := make([]string, 0, len(tag.Paths))
	for _, p := range tag.Paths {
		if p != path {
			newPaths = append(newPaths, p)
		}
	}
	tag.Paths = newPaths

	if err := s.back.Update(tag); err != nil {
		return nil, err
	}
	return tag, nil
}

// RewritePathPrefix updates matching paths in every user's tags. Duplicate
// destinations are collapsed while retaining the original path order.
func (s *Storage) RewritePathPrefix(from, to string) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, tag := range all {
		changed := false
		seen := make(map[string]struct{}, len(tag.Paths))
		next := make([]string, 0, len(tag.Paths))
		for _, savedPath := range tag.Paths {
			rewritten, matched := pathmeta.Rewrite(savedPath, from, to)
			changed = changed || matched && rewritten != savedPath
			if _, exists := seen[rewritten]; exists {
				changed = true
				continue
			}
			seen[rewritten] = struct{}{}
			next = append(next, rewritten)
		}
		if !changed {
			continue
		}

		original := cloneTag(*tag)
		if err := s.back.UpdatePaths(tag.ID, next); err != nil {
			return nil, errors.Join(err, s.RestorePathMutation(mutation))
		}
		mutation.updated = append(mutation.updated, original)
	}
	return mutation, nil
}

// RemovePathPrefix removes matching paths from every user's tags. Empty tags
// remain valid and are not deleted.
func (s *Storage) RemovePathPrefix(prefix string) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, tag := range all {
		next := make([]string, 0, len(tag.Paths))
		for _, savedPath := range tag.Paths {
			if !pathmeta.Contains(savedPath, prefix) {
				next = append(next, pathmeta.Clean(savedPath))
			}
		}
		if len(next) == len(tag.Paths) {
			continue
		}

		original := cloneTag(*tag)
		if err := s.back.UpdatePaths(tag.ID, next); err != nil {
			return nil, errors.Join(err, s.RestorePathMutation(mutation))
		}
		mutation.updated = append(mutation.updated, original)
	}
	return mutation, nil
}

// RestorePathMutation restores only path lists captured by a prior mutation.
func (s *Storage) RestorePathMutation(mutation *PathMutation) error {
	if mutation == nil {
		return nil
	}

	previous := make([]Tag, 0, len(mutation.updated))
	for _, tag := range mutation.updated {
		current, err := s.back.GetByID(tag.UserID, tag.ID)
		if err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}
		previousTag := cloneTag(*current)
		if err := s.back.UpdatePaths(tag.ID, tag.Paths); err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}
		previous = append(previous, previousTag)
	}
	return nil
}

func (s *Storage) restoreUpdatedTags(snapshot []Tag) error {
	var restoreErr error
	for _, tag := range snapshot {
		restoreErr = errors.Join(restoreErr, s.back.UpdatePaths(tag.ID, tag.Paths))
	}
	return restoreErr
}

// RestoreUpdatedSnapshot restores only the exact tag path lists captured by
// UpdatedSnapshot.
func (s *Storage) RestoreUpdatedSnapshot(snapshot []Tag) error {
	updated := make([]Tag, len(snapshot))
	for index, tag := range snapshot {
		updated[index] = cloneTag(tag)
	}
	return s.RestorePathMutation(&PathMutation{updated: updated})
}

// RestoreRemovedSnapshot merges only paths that were removed with a trashed
// resource. It deliberately preserves unrelated tag edits made while the
// resource was in the recycle bin and does not recreate a tag the user has
// since deleted.
func (s *Storage) RestoreRemovedSnapshot(snapshot []Tag, from, to string) error {
	previous := make([]Tag, 0, len(snapshot))
	for _, saved := range snapshot {
		current, err := s.back.GetByID(saved.UserID, saved.ID)
		if errors.Is(err, ErrNotExist) {
			continue
		}
		if err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}

		next := append([]string(nil), current.Paths...)
		seen := make(map[string]struct{}, len(next))
		for _, currentPath := range next {
			seen[pathmeta.Clean(currentPath)] = struct{}{}
		}
		for _, savedPath := range saved.Paths {
			rewritten, matched := pathmeta.Rewrite(savedPath, from, to)
			if !matched {
				continue
			}
			if _, exists := seen[rewritten]; exists {
				continue
			}
			seen[rewritten] = struct{}{}
			next = append(next, rewritten)
		}
		if len(next) == len(current.Paths) {
			continue
		}

		previousTag := cloneTag(*current)
		if err := s.back.UpdatePaths(saved.ID, next); err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}
		previous = append(previous, previousTag)
	}
	return nil
}

func cloneTag(tag Tag) Tag {
	tag.Paths = append([]string(nil), tag.Paths...)
	return tag
}

// generateID creates a unique ID based on timestamp + counter.
var tagIDCounter uint64

func generateID() string {
	c := atomic.AddUint64(&tagIDCounter, 1)
	return time.Now().Format("20060102150405.000") + "-" + fmt.Sprintf("%06x", c&0xFFFFFF)
}
