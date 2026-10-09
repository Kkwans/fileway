package bolt

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/tags"
)

// Tag keeps the historical Storm bucket name while persisting ownership that
// the public domain type intentionally omits from JSON responses.
type Tag struct {
	ID        string   `json:"id" storm:"id"`
	UserID    uint     `json:"userId" storm:"index"`
	Name      string   `json:"name" storm:"index"`
	Color     string   `json:"color"`
	Paths     []string `json:"paths"`
	CreatedAt int64    `json:"createdAt"`
	PathBytes [][]byte `json:"pathBytes,omitempty"`
	NameBytes []byte   `json:"nameBytes,omitempty"`
}

func (tag *Tag) UnmarshalJSON(data []byte) error {
	type recordAlias Tag
	var decoded recordAlias
	if err := json.Unmarshal(data, &decoded); err != nil {
		return err
	}
	*tag = Tag(decoded)
	tag.Name = metadataText(tag.Name, tag.NameBytes)
	if tag.PathBytes != nil {
		if len(tag.PathBytes) != len(tag.Paths) {
			return fmt.Errorf("标签原始路径数量与旧字段不一致")
		}
		for index, raw := range tag.PathBytes {
			if raw != nil {
				tag.Paths[index] = string(raw)
			}
		}
	}
	return nil
}

type tagsBackend struct {
	db *storm.DB
}

func (t tagsBackend) ClaimLegacy(userID uint) error {
	var records []*Tag
	if err := t.db.All(&records); err != nil && !errors.Is(err, storm.ErrNotFound) {
		return err
	}
	for _, tag := range records {
		if tag.UserID == 0 {
			tag.UserID = userID
			if err := t.db.Update(tag); err != nil {
				return err
			}
		}
	}
	return nil
}

func (t tagsBackend) migrateOwners(userIDs []uint) error {
	for _, userID := range userIDs {
		var records []*Tag
		if err := t.db.Find("UserID", userID, &records); err != nil && !errors.Is(err, storm.ErrNotFound) {
			return err
		}
		for _, record := range records {
			if record.UserID == userID {
				continue
			}
			record.UserID = userID
			if err := t.db.Update(record); err != nil {
				return err
			}
		}
	}
	return nil
}

func (t tagsBackend) GetAll(userID uint) ([]*tags.Tag, error) {
	var records []*Tag
	err := t.db.Find("UserID", userID, &records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*tags.Tag{}, nil
	}
	if err != nil {
		return nil, err
	}
	return tagDomains(records), nil
}

func (t tagsBackend) GetAllForPathMutation() ([]*tags.Tag, error) {
	var records []*Tag
	err := t.db.All(&records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*tags.Tag{}, nil
	}
	if err != nil {
		return nil, err
	}
	return tagDomains(records), nil
}

func (t tagsBackend) GetByID(userID uint, id string) (*tags.Tag, error) {
	var record Tag
	err := t.db.One("ID", id, &record)
	if errors.Is(err, storm.ErrNotFound) {
		return nil, tags.ErrNotExist
	}
	if err != nil {
		return nil, err
	}
	if record.UserID == userID {
		return record.domain(), nil
	}
	if record.UserID == 0 {
		record.UserID = userID
		if err := t.db.Update(&record); err != nil {
			return nil, err
		}
		return record.domain(), nil
	}
	return nil, tags.ErrNotExist
}

func (t tagsBackend) Save(tag *tags.Tag) error {
	return t.db.Save(newTagRecord(tag))
}

func (t tagsBackend) Update(tag *tags.Tag) error {
	return mutateMetadataRecord[Tag](t.db, tag.ID, func(record *Tag) { *record = *newTagRecord(tag) })
}

func (t tagsBackend) UpdatePaths(id string, paths []string) error {
	return mutateMetadataRecord[Tag](t.db, id, func(record *Tag) {
		// Legacy callers have no provenance argument. Keep any unchanged old
		// unknown occurrence unknown rather than certifying it during a rewrite.
		current := record.domain()
		unknown := make(map[string]int)
		for index, path := range current.Paths {
			if current.PathIsUnverified(index) {
				unknown[path]++
			}
		}
		flags := make([]bool, len(paths))
		for index, path := range paths {
			if unknown[path] > 0 {
				flags[index] = true
				unknown[path]--
			}
		}
		next := append([]string(nil), paths...)
		for index, path := range current.Paths {
			if current.PathIsUnverified(index) && unknown[path] > 0 {
				next = append(next, path)
				flags = append(flags, true)
				unknown[path]--
			}
		}
		record.Paths = next
		record.PathBytes = tagPathBytes(next, flags)
	})
}

func (t tagsBackend) UpdatePathReferences(id string, paths []string, unverified []bool) error {
	if len(paths) != len(unverified) {
		return fmt.Errorf("标签路径与来源数量不一致")
	}
	return mutateMetadataRecord[Tag](t.db, id, func(record *Tag) {
		record.Paths = append([]string(nil), paths...)
		record.PathBytes = tagPathBytes(paths, unverified)
	})
}

func tagPathBytes(paths []string, unverified []bool) [][]byte {
	raw := metadataPathBytes(paths)
	for index := range raw {
		if index < len(unverified) && unverified[index] {
			raw[index] = nil
		}
	}
	return raw
}

func (t tagsBackend) Delete(id string) error {
	return t.db.DeleteStruct(&Tag{ID: id})
}

func newTagRecord(tag *tags.Tag) *Tag {
	return &Tag{
		ID: tag.ID, UserID: tag.UserID, Name: tag.Name, Color: tag.Color,
		Paths: append([]string(nil), tag.Paths...), CreatedAt: tag.CreatedAt,
		PathBytes: tagPathBytes(tag.Paths, tag.UnverifiedPaths), NameBytes: []byte(tag.Name),
	}
}

func (tag *Tag) domain() *tags.Tag {
	domain := &tags.Tag{
		ID: tag.ID, UserID: tag.UserID, Name: tag.Name, Color: tag.Color,
		Paths: append([]string(nil), tag.Paths...), CreatedAt: tag.CreatedAt,
		UnverifiedPaths: make([]bool, len(tag.Paths)),
	}
	for index, path := range domain.Paths {
		domain.UnverifiedPaths[index] = (tag.PathBytes != nil && tag.PathBytes[index] == nil) ||
			(tag.PathBytes == nil && strings.ContainsRune(path, '\uFFFD'))
	}
	return domain
}

func tagDomains(records []*Tag) []*tags.Tag {
	result := make([]*tags.Tag, len(records))
	for index, record := range records {
		result[index] = record.domain()
	}
	return result
}
