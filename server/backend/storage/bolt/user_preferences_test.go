package bolt

import (
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestNewStorageMigratesAllLegacyListingPreferences(t *testing.T) {
	db := openMetadataTestDB(t)
	legacyVisible := &users.User{ID: 21, Username: "visible"}
	legacyHidden := &users.User{ID: 22, Username: "hidden", HideDotfiles: true}
	existing := &users.User{
		ID:       23,
		Username: "existing",
		ListingPreferences: users.ListingPreferences{
			Version:     users.ListingPreferencesVersion,
			PrefixRules: []users.PrefixRule{{Prefix: "@@", Visible: false, Expanded: false, Order: 0}},
		},
	}
	for _, user := range []*users.User{legacyVisible, legacyHidden, existing} {
		if err := db.Save(user); err != nil {
			t.Fatal(err)
		}
	}

	if _, err := NewStorage(db); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		id         uint
		dotVisible bool
	}{
		{id: legacyVisible.ID, dotVisible: true},
		{id: legacyHidden.ID, dotVisible: false},
	} {
		var stored users.User
		if err := db.One("ID", test.id, &stored); err != nil {
			t.Fatal(err)
		}
		if stored.ListingPreferences.Version != users.ListingPreferencesVersion {
			t.Fatalf("user %d version = %d", test.id, stored.ListingPreferences.Version)
		}
		dot, ok := stored.ListingPreferences.MatchPrefixRule(".hidden")
		if !ok || dot.Visible != test.dotVisible {
			t.Fatalf("user %d dot rule = %#v, %t", test.id, dot, ok)
		}
	}

	var preserved users.User
	if err := db.One("ID", existing.ID, &preserved); err != nil {
		t.Fatal(err)
	}
	if len(preserved.ListingPreferences.PrefixRules) != 1 || preserved.ListingPreferences.PrefixRules[0].Prefix != "@@" {
		t.Fatalf("existing preferences changed: %#v", preserved.ListingPreferences)
	}

	if _, err := NewStorage(db); err != nil {
		t.Fatal(err)
	}
}
