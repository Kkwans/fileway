package bolt

import (
	"encoding/json"
	"errors"
	"strings"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/recent"
)

type recentRecord struct {
	ID         string `storm:"id"`
	UserID     uint   `storm:"index"`
	Path       string `storm:"index"`
	Name       string
	IsDir      bool
	AccessedAt int64  `storm:"index"`
	PathBytes  []byte `json:"pathBytes,omitempty"`
	NameBytes  []byte `json:"nameBytes,omitempty"`
}

func (record *recentRecord) UnmarshalJSON(data []byte) error {
	type recordAlias recentRecord
	var decoded recordAlias
	if err := json.Unmarshal(data, &decoded); err != nil {
		return err
	}
	*record = recentRecord(decoded)
	record.Path = metadataText(record.Path, record.PathBytes)
	record.Name = metadataText(record.Name, record.NameBytes)
	return nil
}

type recentBackend struct {
	db *storm.DB
}

func (backend recentBackend) GetAll() ([]*recent.Entry, error) {
	var records []*recentRecord
	if err := backend.db.All(&records); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return []*recent.Entry{}, nil
		}
		return nil, err
	}
	entries := make([]*recent.Entry, len(records))
	for index, record := range records {
		entries[index] = record.entry()
	}
	return entries, nil
}

func (backend recentBackend) Save(entry *recent.Entry) error {
	return backend.db.Save(newRecentRecord(entry))
}

func (backend recentBackend) Update(entry *recent.Entry) error {
	return mutateMetadataRecord[recentRecord](backend.db, entry.ID, func(record *recentRecord) { *record = *newRecentRecord(entry) })
}

func (backend recentBackend) Delete(id string) error {
	if err := backend.db.DeleteStruct(&recentRecord{ID: id}); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return recent.ErrNotExist
		}
		return err
	}
	return nil
}

func newRecentRecord(entry *recent.Entry) *recentRecord {
	record := &recentRecord{
		ID: entry.ID, UserID: entry.UserID, Path: entry.Path, Name: entry.Name,
		IsDir: entry.IsDir, AccessedAt: entry.AccessedAt,
		NameBytes: []byte(entry.Name),
	}
	if !entry.PathUnverified {
		record.PathBytes = []byte(entry.Path)
	}
	return record
}

func (record *recentRecord) entry() *recent.Entry {
	return &recent.Entry{
		ID: record.ID, UserID: record.UserID, Path: record.Path, Name: record.Name,
		IsDir: record.IsDir, AccessedAt: record.AccessedAt,
		PathUnverified: record.PathBytes == nil && strings.ContainsRune(record.Path, '\uFFFD'),
	}
}
