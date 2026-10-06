package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/recent"
)

type recentRecord struct {
	ID         string `storm:"id"`
	UserID     uint   `storm:"index"`
	Path       string `storm:"index"`
	Name       string
	IsDir      bool
	AccessedAt int64 `storm:"index"`
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
	record := newRecentRecord(entry)
	if err := backend.db.Update(record); err != nil {
		return err
	}
	return backend.db.UpdateField(&recentRecord{ID: entry.ID}, "IsDir", entry.IsDir)
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
	return &recentRecord{
		ID: entry.ID, UserID: entry.UserID, Path: entry.Path, Name: entry.Name,
		IsDir: entry.IsDir, AccessedAt: entry.AccessedAt,
	}
}

func (record *recentRecord) entry() *recent.Entry {
	return &recent.Entry{
		ID: record.ID, UserID: record.UserID, Path: record.Path, Name: record.Name,
		IsDir: record.IsDir, AccessedAt: record.AccessedAt,
	}
}
