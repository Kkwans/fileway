package users

import "testing"

func TestDefaultListingPreferencesMigratesLegacyDotfiles(t *testing.T) {
	visible := DefaultListingPreferences(false)
	hidden := DefaultListingPreferences(true)

	if visible.Version != ListingPreferencesVersion || hidden.Version != ListingPreferencesVersion {
		t.Fatalf("versions = visible:%d hidden:%d", visible.Version, hidden.Version)
	}
	if len(visible.PrefixRules) != 5 || len(hidden.PrefixRules) != 5 {
		t.Fatalf("rules = visible:%#v hidden:%#v", visible.PrefixRules, hidden.PrefixRules)
	}
	if !visible.PrefixRules[0].Visible || hidden.PrefixRules[0].Visible {
		t.Fatalf("dot visibility = visible:%t hidden:%t", visible.PrefixRules[0].Visible, hidden.PrefixRules[0].Visible)
	}
	for _, preferences := range []ListingPreferences{visible, hidden} {
		for _, rule := range preferences.PrefixRules {
			if !rule.Expanded {
				t.Fatalf("default rule collapsed: %#v", rule)
			}
		}
	}
}

func TestNormalizeListingPreferencesValidatesAndCompletesRules(t *testing.T) {
	preferences, err := NormalizeListingPreferences(ListingPreferences{
		Version: ListingPreferencesVersion,
		PrefixRules: []PrefixRule{
			{Prefix: "@@", Visible: false, Expanded: false, Order: 8},
			{Prefix: "@", Visible: true, Expanded: true, Order: 3},
		},
	}, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(preferences.PrefixRules) != 6 {
		t.Fatalf("rules = %#v", preferences.PrefixRules)
	}
	matched, ok := preferences.MatchPrefixRule("@@cache")
	if !ok || matched.Prefix != "@@" || matched.Visible || matched.Expanded {
		t.Fatalf("longest match = %#v, %t", matched, ok)
	}

	invalid := []ListingPreferences{
		{Version: 2},
		{Version: ListingPreferencesVersion, PrefixRules: []PrefixRule{{Prefix: ""}}},
		{Version: ListingPreferencesVersion, PrefixRules: []PrefixRule{{Prefix: "a/b"}}},
		{Version: ListingPreferencesVersion, PrefixRules: []PrefixRule{{Prefix: " "}}},
		{Version: ListingPreferencesVersion, PrefixRules: []PrefixRule{{Prefix: "@"}, {Prefix: "@"}}},
	}
	for index, value := range invalid {
		if _, err := NormalizeListingPreferences(value, false); err == nil {
			t.Fatalf("invalid case %d accepted: %#v", index, value)
		}
	}
}

func TestNormalizeListingPreferencesLimitsCustomRules(t *testing.T) {
	rules := make([]PrefixRule, 0, MaxCustomPrefixRules+1)
	for index := 0; index <= MaxCustomPrefixRules; index++ {
		rules = append(rules, PrefixRule{Prefix: string(rune(0x4e00 + index)), Visible: true, Expanded: true, Order: index})
	}
	_, err := NormalizeListingPreferences(ListingPreferences{Version: ListingPreferencesVersion, PrefixRules: rules}, false)
	if err == nil {
		t.Fatal("custom prefix limit was not enforced")
	}
}
