package fbhttp

import (
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestHiddenListingPreferenceDoesNotBlockDirectPaths(t *testing.T) {
	d := &data{
		settings: &settings.Settings{},
		user:     &users.User{HideDotfiles: true},
	}
	if !d.Check("/.hidden/file.txt") {
		t.Fatal("legacy dotfile preference blocked direct path access")
	}
}
