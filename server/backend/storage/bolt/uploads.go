package bolt

import (
	"errors"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
	"github.com/asdine/storm/v3"
)

// New bucket only. Paths are ASCII wire values; legacy filesystem bytes never
// pass through encoding/json's replacement-character conversion.
type uploadRecord struct {
	ID             string `storm:"id"`
	UserID         uint   `storm:"index"`
	WirePath       string
	PartWire       string
	RootKey        string
	Overwrite      bool
	TargetIdentity *files.Identity
	TargetSize     int64
	TargetModified int64
	Length         int64
	Offset         int64
	Modified       int64
	Writing        bool
	ReservedTo     int64
	Identity       *files.Identity
	State          uploads.State `storm:"index"`
	CreatedAt      int64
	UpdatedAt      int64
	ExpiresAt      int64 `storm:"index"`
}
type uploadBackend struct{ db *storm.DB }

func (b uploadBackend) Get(id string) (*uploads.Session, error) {
	var record uploadRecord
	if err := b.db.One("ID", id, &record); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil, uploads.ErrNotExist
		}
		return nil, err
	}
	return record.session(), nil
}
func (b uploadBackend) Save(row *uploads.Session) error {
	// Save replaces all fields, including Offset=0 and empty/error states;
	// Storm Update's omitted zero values must not leave a false old checkpoint.
	return b.db.Save(&uploadRecord{ID: row.ID, UserID: row.UserID, WirePath: row.WirePath, PartWire: row.PartWire, RootKey: row.RootKey,
		Overwrite: row.Overwrite, TargetIdentity: row.TargetIdentity, TargetSize: row.TargetSize, TargetModified: row.TargetModified,
		Length: row.Length, Offset: row.Offset, Modified: row.Modified, Writing: row.Writing, ReservedTo: row.ReservedTo, Identity: row.Identity, State: row.State,
		CreatedAt: row.CreatedAt, UpdatedAt: row.UpdatedAt, ExpiresAt: row.ExpiresAt})
}
func (b uploadBackend) All() ([]*uploads.Session, error) {
	var records []*uploadRecord
	if err := b.db.All(&records); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return []*uploads.Session{}, nil
		}
		return nil, err
	}
	rows := make([]*uploads.Session, len(records))
	for index, row := range records {
		rows[index] = row.session()
	}
	return rows, nil
}
func (r *uploadRecord) session() *uploads.Session {
	return &uploads.Session{ID: r.ID, UserID: r.UserID, WirePath: r.WirePath, RootKey: r.RootKey,
		PartWire: r.PartWire, Overwrite: r.Overwrite, TargetIdentity: r.TargetIdentity, TargetSize: r.TargetSize, TargetModified: r.TargetModified,
		Length: r.Length, Offset: r.Offset, Modified: r.Modified, Writing: r.Writing, ReservedTo: r.ReservedTo, Identity: r.Identity, State: r.State, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt, ExpiresAt: r.ExpiresAt}
}
