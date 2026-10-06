package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func migrateUserListingPreferences(db *storm.DB) error {
	var allUsers []*users.User
	if err := db.All(&allUsers); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return nil
		}
		return err
	}

	for _, user := range allUsers {
		if user.ListingPreferences.Version != 0 {
			continue
		}
		user.ListingPreferences = users.DefaultListingPreferences(user.HideDotfiles)
		if err := db.UpdateField(user, "ListingPreferences", user.ListingPreferences); err != nil {
			return err
		}
	}
	return nil
}
