package favorites

import (
	"encoding/json"
	"testing"
)

func TestFavoritePublicWireIdentityAndLegacyProvenance(t *testing.T) {
	for _, test := range []struct {
		favorite Favorite
		wire     string
		verified bool
	}{
		{Favorite{ID: "opaque", UserID: 7, Path: "/\xd6\xd0\xce\xc4.txt", Name: "\xd6\xd0\xce\xc4.txt"}, "/%D6%D0%CE%C4.txt", true},
		{Favorite{ID: "legacy", UserID: 7, Path: "/lost�.txt", Name: "old", PathUnverified: true}, "", false},
		{Favorite{ID: "actual-replacement-character", UserID: 7, Path: "/lost�.txt", Name: "actual"}, "/lost%EF%BF%BD.txt", true},
	} {
		body, err := json.Marshal(&test.favorite)
		if err != nil {
			t.Fatal(err)
		}
		var row map[string]any
		if err := json.Unmarshal(body, &row); err != nil {
			t.Fatal(err)
		}
		if row["pathVerified"] != test.verified {
			t.Fatalf("path provenance=%s", body)
		}
		if test.wire == "" {
			if value, exists := row["wirePath"]; exists && value != "" {
				t.Fatalf("legacy path claimed bytes=%s", body)
			}
		} else if row["wirePath"] != test.wire {
			t.Fatalf("wire identity=%s want=%q", body, test.wire)
		}
		if _, exists := row["userId"]; exists {
			t.Fatal("public favorite exposed owner storage field")
		}
		if test.favorite.ID == "opaque" && (row["path"] != "/中文.txt" || row["name"] != "中文.txt") {
			t.Fatalf("opaque display=%s", body)
		}
	}
}
