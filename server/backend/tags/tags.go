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
	// Position-aligned provenance; public display strings alone are not identity.
	UnverifiedPaths []bool `json:"-"`
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

// PathIdentityStorageBackend preserves per-reference provenance while updating
// paths without overwriting concurrently edited names/colors.
type PathIdentityStorageBackend interface {
	UpdatePathReferences(id string, paths []string, unverified []bool) error
}

func (tag *Tag) PathIsUnverified(index int) bool {
	return index >= 0 && index < len(tag.UnverifiedPaths) && tag.UnverifiedPaths[index]
}

func pathFlags(tag *Tag) []bool {
	flags := make([]bool, len(tag.Paths))
	copy(flags, tag.UnverifiedPaths)
	return flags
}

func (s *Storage) updatePaths(tag *Tag) error {
	for _, unverified := range tag.UnverifiedPaths {
		if !unverified {
			continue
		}
		if backend, ok := s.back.(PathIdentityStorageBackend); ok {
			return backend.UpdatePathReferences(tag.ID, tag.Paths, pathFlags(tag))
		}
		return s.back.Update(tag)
	}
	return s.back.UpdatePaths(tag.ID, tag.Paths)
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

	for index, p := range tag.Paths {
		if !tag.PathIsUnverified(index) && p == path {
			return tag, nil // already exists
		}
	}

	tag.UnverifiedPaths = append(pathFlags(tag), false)
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
	newFlags := make([]bool, 0, len(tag.Paths))
	for index, p := range tag.Paths {
		if tag.PathIsUnverified(index) || p != path {
			newPaths = append(newPaths, p)
			newFlags = append(newFlags, tag.PathIsUnverified(index))
		}
	}
	tag.Paths = newPaths
	tag.UnverifiedPaths = newFlags

	if err := s.back.Update(tag); err != nil {
		return nil, err
	}
	return tag, nil
}

// RewritePathPrefix updates matching paths in every user's tags. Duplicate
// destinations are collapsed while retaining the original path order.
func (s *Storage) RewritePathPrefix(from, to string, mapper ...pathmeta.Mapper) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, tag := range all {
		changed := false
		seen := make(map[string]struct{}, len(tag.Paths))
		next := make([]string, 0, len(tag.Paths))
		nextFlags := make([]bool, 0, len(tag.Paths))
		for index, savedPath := range tag.Paths {
			if tag.PathIsUnverified(index) {
				next = append(next, savedPath)
				nextFlags = append(nextFlags, true)
				continue
			}
			rewritten, matched := pathmeta.RewriteForUser(tag.UserID, savedPath, from, to, mapper...)
			changed = changed || matched && rewritten != savedPath
			if matched && rewritten == "" {
				continue
			}
			if _, exists := seen[rewritten]; exists {
				continue
			}
			seen[rewritten] = struct{}{}
			next = append(next, rewritten)
			nextFlags = append(nextFlags, false)
		}
		if !changed {
			continue
		}

		original := cloneTag(*tag)
		updated := cloneTag(*tag)
		updated.Paths = next
		updated.UnverifiedPaths = nextFlags
		if err := s.updatePaths(&updated); err != nil {
			return nil, errors.Join(err, s.RestorePathMutation(mutation))
		}
		mutation.updated = append(mutation.updated, original)
	}
	return mutation, nil
}

// RemovePathPrefix removes matching paths from every user's tags. Empty tags
// remain valid and are not deleted.
func (s *Storage) RemovePathPrefix(prefix string, mapper ...pathmeta.Mapper) (*PathMutation, error) {
	all, err := s.back.GetAllForPathMutation()
	if err != nil {
		return nil, err
	}

	mutation := &PathMutation{}
	for _, tag := range all {
		next := make([]string, 0, len(tag.Paths))
		nextFlags := make([]bool, 0, len(tag.Paths))
		for index, savedPath := range tag.Paths {
			_, matched := pathmeta.RewriteForUser(tag.UserID, savedPath, prefix, prefix, mapper...)
			if tag.PathIsUnverified(index) {
				next = append(next, savedPath)
				nextFlags = append(nextFlags, true)
			} else if !matched {
				next = append(next, pathmeta.Clean(savedPath))
				nextFlags = append(nextFlags, false)
			}
		}
		if len(next) == len(tag.Paths) {
			continue
		}

		original := cloneTag(*tag)
		updated := cloneTag(*tag)
		updated.Paths = next
		updated.UnverifiedPaths = nextFlags
		if err := s.updatePaths(&updated); err != nil {
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
		if err := s.updatePaths(&tag); err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}
		previous = append(previous, previousTag)
	}
	return nil
}

func (s *Storage) restoreUpdatedTags(snapshot []Tag) error {
	var restoreErr error
	for _, tag := range snapshot {
		restoreErr = errors.Join(restoreErr, s.updatePaths(&tag))
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
func (s *Storage) RestoreRemovedSnapshot(snapshot []Tag, from, to string, mapper ...pathmeta.Mapper) error {
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
		nextFlags := pathFlags(current)
		seen := make(map[string]struct{}, len(next))
		for index, currentPath := range next {
			if !current.PathIsUnverified(index) {
				seen[pathmeta.Clean(currentPath)] = struct{}{}
			}
		}
		for index, savedPath := range saved.Paths {
			if saved.PathIsUnverified(index) {
				continue
			}
			rewritten, matched := pathmeta.RewriteForUser(saved.UserID, savedPath, from, to, mapper...)
			if !matched || rewritten == "" {
				continue
			}
			if _, exists := seen[rewritten]; exists {
				continue
			}
			seen[rewritten] = struct{}{}
			next = append(next, rewritten)
			nextFlags = append(nextFlags, false)
		}
		if len(next) == len(current.Paths) {
			continue
		}

		previousTag := cloneTag(*current)
		updated := cloneTag(*current)
		updated.Paths = next
		updated.UnverifiedPaths = nextFlags
		if err := s.updatePaths(&updated); err != nil {
			return errors.Join(err, s.restoreUpdatedTags(previous))
		}
		previous = append(previous, previousTag)
	}
	return nil
}

func cloneTag(tag Tag) Tag {
	tag.Paths = append([]string(nil), tag.Paths...)
	tag.UnverifiedPaths = append([]bool(nil), tag.UnverifiedPaths...)
	return tag
}

// generateID creates a unique ID based on timestamp + counter.
var tagIDCounter uint64

func generateID() string {
	c := atomic.AddUint64(&tagIDCounter, 1)
	return time.Now().Format("20060102150405.000") + "-" + fmt.Sprintf("%06x", c&0xFFFFFF)
}
