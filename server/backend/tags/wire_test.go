package tags

import (
	"encoding/json"
	"testing"
)

func TestTagPublicReferencesKeepUnknownAndTrustedSameDisplayPaths(t *testing.T) {
	tag := &Tag{ID: "owned", UserID: 7, Paths: []string{"/lost�", "/lost�", "/\xd6\xd0.txt"}, UnverifiedPaths: []bool{true, false, false}}
	body, err := json.Marshal(tag)
	if err != nil {
		t.Fatal(err)
	}
	var row struct {
		PathRefs []struct {
			Path, WirePath string
			PathVerified   bool
		}
	}
	if json.Unmarshal(body, &row) != nil || len(row.PathRefs) != 3 {
		t.Fatalf("refs=%s", body)
	}
	if row.PathRefs[0].PathVerified || row.PathRefs[0].WirePath != "" || !row.PathRefs[1].PathVerified || row.PathRefs[1].WirePath != "/lost%EF%BF%BD" || row.PathRefs[2].WirePath != "/%D6%D0.txt" {
		t.Fatalf("provenance=%s", body)
	}
}

func TestTrustedLegacyAliasesUseCanonicalWireWithoutChangingStoredBytes(t *testing.T) {
	original := "//folder/../literal%2F.txt"
	backend := &memoryBackend{tags: []*Tag{{ID: "legacy", UserID: 7, Paths: []string{original}}}}
	store := NewStorage(backend)
	row, err := store.GetByID(7, "legacy")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(row)
	var dto struct {
		PathRefs []struct{ Path, WirePath string }
	}
	if json.Unmarshal(body, &dto) != nil || dto.PathRefs[0].WirePath != "/literal%252F.txt" || dto.PathRefs[0].Path != "/literal%2F.txt" {
		t.Fatalf("legacy projection=%s", body)
	}
	if backend.tags[0].Paths[0] != original {
		t.Fatal("public projection migrated stored row")
	}
	updated, err := store.RemovePath(7, "legacy", "/literal%2F.txt")
	if err != nil || len(updated.Paths) != 0 {
		t.Fatalf("canonical unlink=%#v %v", updated, err)
	}
}
