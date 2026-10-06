package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"
	"github.com/asdine/storm/v3/q"

	"github.com/Kkwans/nas-file-browser/backend/transfers"
)

type transferRecord struct {
	ID               string           `storm:"id"`
	UserID           uint             `storm:"index"`
	Kind             transfers.Kind   `storm:"index"`
	Status           transfers.Status `storm:"index"`
	Name             string
	Target           string
	BytesTotal       int64
	BytesTransferred int64
	Error            string
	CreatedAt        int64 `storm:"index"`
	StartedAt        int64
	FinishedAt       int64
	BatchID          string `storm:"index"`
	BatchName        string
	BatchItems       int
	BatchBytes       int64
	IsFolderUpload   bool
}

type transferBackend struct {
	db *storm.DB
}

func (backend transferBackend) GetAll() ([]*transfers.Item, error) {
	var records []*transferRecord
	if err := backend.db.All(&records); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return []*transfers.Item{}, nil
		}
		return nil, err
	}
	items := make([]*transfers.Item, len(records))
	for index, record := range records {
		items[index] = record.item()
	}
	return items, nil
}

func (backend transferBackend) GetByID(id string) (*transfers.Item, error) {
	var record transferRecord
	if err := backend.db.One("ID", id, &record); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil, transfers.ErrNotExist
		}
		return nil, err
	}
	return record.item(), nil
}

func (backend transferBackend) ListRecent(userID uint, kind transfers.Kind, limit int, after *transfers.RecentCursor) ([]*transfers.Item, error) {
	return backend.ListRecentFiltered(userID, kind, nil, limit, after)
}

func (backend transferBackend) ListRecentFiltered(userID uint, kind transfers.Kind, statuses []transfers.Status, limit int, after *transfers.RecentCursor) ([]*transfers.Item, error) {
	if limit < 1 {
		return []*transfers.Item{}, nil
	}
	matchers := []q.Matcher{q.Eq("UserID", userID)}
	if kind != "" {
		matchers = append(matchers, q.Eq("Kind", kind))
	}
	if len(statuses) > 0 {
		statusMatchers := make([]q.Matcher, 0, len(statuses))
		for _, status := range statuses {
			statusMatchers = append(statusMatchers, q.Eq("Status", status))
		}
		matchers = append(matchers, q.Or(statusMatchers...))
	}
	if after != nil {
		matchers = append(matchers, q.Or(
			q.Lt("CreatedAt", after.CreatedAt),
			q.And(q.Eq("CreatedAt", after.CreatedAt), q.Lt("ID", after.ID)),
		))
	}
	var records []*transferRecord
	err := backend.db.Select(matchers...).OrderBy("CreatedAt", "ID").Reverse().Limit(limit).Find(&records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*transfers.Item{}, nil
	}
	if err != nil {
		return nil, err
	}
	items := make([]*transfers.Item, len(records))
	for index, record := range records {
		items[index] = record.item()
	}
	return items, nil
}

func (backend transferBackend) Count(userID uint, kind transfers.Kind) (int, error) {
	return backend.CountFiltered(userID, kind, nil)
}

func (backend transferBackend) CountFiltered(userID uint, kind transfers.Kind, statuses []transfers.Status) (int, error) {
	matchers := []q.Matcher{q.Eq("UserID", userID)}
	if kind != "" {
		matchers = append(matchers, q.Eq("Kind", kind))
	}
	if len(statuses) > 0 {
		statusMatchers := make([]q.Matcher, 0, len(statuses))
		for _, status := range statuses {
			statusMatchers = append(statusMatchers, q.Eq("Status", status))
		}
		matchers = append(matchers, q.Or(statusMatchers...))
	}
	count, err := backend.db.Select(matchers...).Count(&transferRecord{})
	if errors.Is(err, storm.ErrNotFound) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return count, nil
}

func (backend transferBackend) Save(item *transfers.Item) error {
	return backend.db.Save(newTransferRecord(item))
}

func (backend transferBackend) Update(item *transfers.Item) error {
	record := newTransferRecord(item)
	if err := backend.db.Update(record); err != nil {
		return err
	}
	// Storm omits zero values during updates; explicitly mirror fields that can
	// be cleared when a transfer is retried or a diagnostic is resolved.
	for field, value := range map[string]interface{}{
		"BytesTotal": item.BytesTotal, "BytesTransferred": item.BytesTransferred,
		"StartedAt": item.StartedAt, "FinishedAt": item.FinishedAt,
		"Error": item.Error, "BatchID": item.BatchID, "BatchName": item.BatchName,
		"BatchItems": item.BatchItems, "BatchBytes": item.BatchBytes,
		"IsFolderUpload": item.IsFolderUpload,
	} {
		if err := backend.db.UpdateField(&transferRecord{ID: item.ID}, field, value); err != nil {
			return err
		}
	}
	return nil
}

func (backend transferBackend) Delete(id string) error {
	if err := backend.db.DeleteStruct(&transferRecord{ID: id}); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return transfers.ErrNotExist
		}
		return err
	}
	return nil
}

func newTransferRecord(item *transfers.Item) *transferRecord {
	return &transferRecord{
		ID: item.ID, UserID: item.UserID, Kind: item.Kind, Status: item.Status,
		Name: item.Name, Target: item.Target, BytesTotal: item.BytesTotal,
		BytesTransferred: item.BytesTransferred, Error: item.Error,
		CreatedAt: item.CreatedAt, StartedAt: item.StartedAt, FinishedAt: item.FinishedAt,
		BatchID: item.BatchID, BatchName: item.BatchName, BatchItems: item.BatchItems,
		BatchBytes: item.BatchBytes, IsFolderUpload: item.IsFolderUpload,
	}
}

func (record *transferRecord) item() *transfers.Item {
	return &transfers.Item{
		ID: record.ID, UserID: record.UserID, Kind: record.Kind, Status: record.Status,
		Name: record.Name, Target: record.Target, BytesTotal: record.BytesTotal,
		BytesTransferred: record.BytesTransferred, Error: record.Error,
		CreatedAt: record.CreatedAt, StartedAt: record.StartedAt, FinishedAt: record.FinishedAt,
		BatchID: record.BatchID, BatchName: record.BatchName, BatchItems: record.BatchItems,
		BatchBytes: record.BatchBytes, IsFolderUpload: record.IsFolderUpload,
	}
}
