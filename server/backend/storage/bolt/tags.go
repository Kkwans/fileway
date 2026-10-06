package bolt

import (
	"errors"

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
	return t.db.Update(newTagRecord(tag))
}

func (t tagsBackend) UpdatePaths(id string, paths []string) error {
	return t.db.UpdateField(&Tag{ID: id}, "Paths", append([]string(nil), paths...))
}

func (t tagsBackend) Delete(id string) error {
	return t.db.DeleteStruct(&Tag{ID: id})
}

func newTagRecord(tag *tags.Tag) *Tag {
	return &Tag{
		ID: tag.ID, UserID: tag.UserID, Name: tag.Name, Color: tag.Color,
		Paths: append([]string(nil), tag.Paths...), CreatedAt: tag.CreatedAt,
	}
}

func (tag *Tag) domain() *tags.Tag {
	return &tags.Tag{
		ID: tag.ID, UserID: tag.UserID, Name: tag.Name, Color: tag.Color,
		Paths: append([]string(nil), tag.Paths...), CreatedAt: tag.CreatedAt,
	}
}

func tagDomains(records []*Tag) []*tags.Tag {
	result := make([]*tags.Tag, len(records))
	for index, record := range records {
		result[index] = record.domain()
	}
	return result
}
