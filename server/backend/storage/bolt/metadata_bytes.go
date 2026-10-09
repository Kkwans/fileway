package bolt

import "github.com/asdine/storm/v3"

// Storm's JSON codec replaces invalid UTF-8 in strings. Additive []byte fields
// use JSON's reversible base64 encoding, while historical fields/buckets/indexes
// remain in place. Never use public display DTOs as persistence records.
func metadataText(legacy string, raw []byte) string {
	if raw != nil {
		return string(raw)
	}
	return legacy
}

func metadataPathBytes(paths []string) [][]byte {
	result := make([][]byte, len(paths))
	for index, path := range paths {
		result[index] = []byte(path)
	}
	return result
}

// mutateMetadataRecord replaces a complete row in one write transaction,
// including zero values. Storm.Update would skip empty path arrays/byte fields.
// Reading first preserves Update's not-found behavior instead of upserting IDs.
func mutateMetadataRecord[T any](db *storm.DB, id string, mutate func(*T)) error {
	tx, err := db.Begin(true)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var record T
	if err := tx.One("ID", id, &record); err != nil {
		return err
	}
	mutate(&record)
	if err := tx.Save(&record); err != nil {
		return err
	}
	return tx.Commit()
}
