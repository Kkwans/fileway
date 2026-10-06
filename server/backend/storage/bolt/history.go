package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/history"
)

// historyRecord keeps ownership durable even though the HTTP/domain JSON
// representation intentionally omits UserID.
type historyRecord struct {
	ID        string `storm:"id"`
	UserID    uint   `storm:"index"`
	Action    string `storm:"index"`
	Target    string
	Detail    string
	Status    history.Status `storm:"index"`
	CreatedAt int64          `storm:"index"`
}

type historyBackend struct {
	db *storm.DB
}

func (backend historyBackend) GetByUser(userID uint) ([]*history.Entry, error) {
	var records []*historyRecord
	if err := backend.db.Find("UserID", userID, &records); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return []*history.Entry{}, nil
		}
		return nil, err
	}
	entries := make([]*history.Entry, len(records))
	for index, record := range records {
		entries[index] = record.entry()
	}
	return entries, nil
}

func (backend historyBackend) Save(entry *history.Entry) error {
	return backend.db.Save(newHistoryRecord(entry))
}

func (backend historyBackend) Delete(id string) error {
	if err := backend.db.DeleteStruct(&historyRecord{ID: id}); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return history.ErrNotExist
		}
		return err
	}
	return nil
}

func newHistoryRecord(entry *history.Entry) *historyRecord {
	return &historyRecord{
		ID: entry.ID, UserID: entry.UserID, Action: entry.Action,
		Target: entry.Target, Detail: entry.Detail, Status: entry.Status,
		CreatedAt: entry.CreatedAt,
	}
}

func (record *historyRecord) entry() *history.Entry {
	return &history.Entry{
		ID: record.ID, UserID: record.UserID, Action: record.Action,
		Target: record.Target, Detail: record.Detail, Status: record.Status,
		CreatedAt: record.CreatedAt,
	}
}
