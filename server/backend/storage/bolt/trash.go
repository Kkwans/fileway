package bolt

import (
	"encoding/json"
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/Kkwans/nas-file-browser/backend/trash"
)

// trashRecord isolates Storm's flat persisted representation from the public
// domain type. In particular, staged metadata and the hidden storage path can
// never leak because HTTP code only receives trash.Item values.
type trashRecord struct {
	SizeState    trash.SizeState
	SizeTaskID   string
	ID           string `storm:"id"`
	UserID       uint   `storm:"index"`
	OwnerName    string
	OriginalPath string `storm:"index"`
	StoredPath   string `storm:"unique"`
	Name         string
	IsDir        bool
	Size         int64
	DeletedAt    int64        `storm:"index"`
	Status       trash.Status `storm:"index"`
	LastError    string
	Metadata     string
}

type trashMetadata struct {
	Favorites []favoriteSnapshotRecord `json:"favorites,omitempty"`
	Tags      []tagSnapshotRecord      `json:"tags,omitempty"`
}

// Snapshot records intentionally include UserID even though the normal HTTP
// representations of favorites and tags hide it. Recycle-bin restoration
// must retain the original owner across process restarts.
type favoriteSnapshotRecord struct {
	ID      string `json:"id"`
	UserID  uint   `json:"userId"`
	Path    string `json:"path"`
	Name    string `json:"name"`
	GroupID string `json:"groupId,omitempty"`
	AddedAt int64  `json:"addedAt"`
	Order   int    `json:"order"`
}

type tagSnapshotRecord struct {
	ID        string   `json:"id"`
	UserID    uint     `json:"userId"`
	Name      string   `json:"name"`
	Color     string   `json:"color"`
	Paths     []string `json:"paths"`
	CreatedAt int64    `json:"createdAt"`
}

type trashBackend struct {
	db *storm.DB
}

func (backend trashBackend) GetAll() ([]*trash.Item, error) {
	var records []*trashRecord
	err := backend.db.All(&records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*trash.Item{}, nil
	}
	if err != nil {
		return nil, err
	}
	items := make([]*trash.Item, 0, len(records))
	for _, record := range records {
		item, err := record.item()
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, nil
}

func (backend trashBackend) GetByID(id string) (*trash.Item, error) {
	var record trashRecord
	if err := backend.db.One("ID", id, &record); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil, trash.ErrNotExist
		}
		return nil, err
	}
	return record.item()
}

func (backend trashBackend) Save(item *trash.Item) error {
	record, err := newTrashRecord(item)
	if err != nil {
		return err
	}
	return backend.db.Save(record)
}

func (backend trashBackend) Update(item *trash.Item) error {
	record, err := newTrashRecord(item)
	if err != nil {
		return err
	}
	tx, err := backend.db.Begin(true)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := tx.Update(record); err != nil {
		return err
	}
	// Force zero bytes and cleared diagnostics in the same transaction as the state.
	if err := tx.UpdateField(&trashRecord{ID: item.ID}, "LastError", item.LastError); err != nil {
		return err
	}
	if err := tx.UpdateField(&trashRecord{ID: item.ID}, "Size", item.Size); err != nil {
		return err
	}
	return tx.Commit()
}

func (backend trashBackend) Delete(id string) error {
	if err := backend.db.DeleteStruct(&trashRecord{ID: id}); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return trash.ErrNotExist
		}
		return err
	}
	return nil
}

func newTrashRecord(item *trash.Item) (*trashRecord, error) {
	metadata, err := json.Marshal(trashMetadata{
		Favorites: favoriteSnapshotRecords(item.FavoriteSnapshots),
		Tags:      tagSnapshotRecords(item.TagSnapshots),
	})
	if err != nil {
		return nil, err
	}
	return &trashRecord{
		SizeState: item.SizeState, SizeTaskID: item.SizeTaskID,
		ID:           item.ID,
		UserID:       item.UserID,
		OwnerName:    item.OwnerName,
		OriginalPath: item.OriginalPath,
		StoredPath:   item.StoredPath,
		Name:         item.Name,
		IsDir:        item.IsDir,
		Size:         item.Size,
		DeletedAt:    item.DeletedAt,
		Status:       item.Status,
		LastError:    item.LastError,
		Metadata:     string(metadata),
	}, nil
}

func (record *trashRecord) item() (*trash.Item, error) {
	var metadata trashMetadata
	if record.Metadata != "" {
		if err := json.Unmarshal([]byte(record.Metadata), &metadata); err != nil {
			return nil, err
		}
	}
	return &trash.Item{
		SizeState: record.SizeState, SizeTaskID: record.SizeTaskID,
		ID:                record.ID,
		UserID:            record.UserID,
		OwnerName:         record.OwnerName,
		OriginalPath:      record.OriginalPath,
		StoredPath:        record.StoredPath,
		Name:              record.Name,
		IsDir:             record.IsDir,
		Size:              record.Size,
		DeletedAt:         record.DeletedAt,
		Status:            record.Status,
		LastError:         record.LastError,
		FavoriteSnapshots: favoriteSnapshots(metadata.Favorites),
		TagSnapshots:      tagSnapshots(metadata.Tags),
	}, nil
}

func favoriteSnapshotRecords(snapshot []favorites.Favorite) []favoriteSnapshotRecord {
	records := make([]favoriteSnapshotRecord, len(snapshot))
	for index, favorite := range snapshot {
		records[index] = favoriteSnapshotRecord{
			ID: favorite.ID, UserID: favorite.UserID, Path: favorite.Path,
			Name: favorite.Name, GroupID: favorite.GroupID,
			AddedAt: favorite.AddedAt, Order: favorite.Order,
		}
	}
	return records
}

func favoriteSnapshots(records []favoriteSnapshotRecord) []favorites.Favorite {
	snapshot := make([]favorites.Favorite, len(records))
	for index, record := range records {
		snapshot[index] = favorites.Favorite{
			ID: record.ID, UserID: record.UserID, Path: record.Path,
			Name: record.Name, GroupID: record.GroupID,
			AddedAt: record.AddedAt, Order: record.Order,
		}
	}
	return snapshot
}

func tagSnapshotRecords(snapshot []tags.Tag) []tagSnapshotRecord {
	records := make([]tagSnapshotRecord, len(snapshot))
	for index, tag := range snapshot {
		records[index] = tagSnapshotRecord{
			ID: tag.ID, UserID: tag.UserID, Name: tag.Name, Color: tag.Color,
			Paths: append([]string(nil), tag.Paths...), CreatedAt: tag.CreatedAt,
		}
	}
	return records
}

func tagSnapshots(records []tagSnapshotRecord) []tags.Tag {
	snapshot := make([]tags.Tag, len(records))
	for index, record := range records {
		snapshot[index] = tags.Tag{
			ID: record.ID, UserID: record.UserID, Name: record.Name,
			Color: record.Color, Paths: append([]string(nil), record.Paths...),
			CreatedAt: record.CreatedAt,
		}
	}
	return snapshot
}
