package bolt

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/tags"
	"github.com/asdine/storm/v3"
)

func TestMetadataRawBytesSurviveCloseReopenAndEveryPathUpdate(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "metadata-bytes.db")
	var db *storm.DB
	reopen := func() {
		t.Helper()
		if db != nil {
			if err := db.Close(); err != nil {
				t.Fatal(err)
			}
			db = nil
		}
		var err error
		db, err = storm.Open(databasePath)
		if err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() {
		if db != nil {
			if err := db.Close(); err != nil {
				t.Error(err)
			}
		}
	})
	reopen()
	rawPath := "/\xd6\xd0\xce\xc4/100% +?#.txt"
	rawName := "\xd6\xd0\xce\xc4.txt"
	recentRow := &recent.Entry{ID: "recent-raw", UserID: 7, Path: rawPath, Name: rawName, IsDir: true, AccessedAt: 10}
	favoriteRow := &favorites.Favorite{ID: "favorite-raw", UserID: 7, Path: rawPath, Name: rawName, GroupID: "owned-group", AddedAt: 10, Order: 3}
	tagRow := &tags.Tag{ID: "tag-raw", UserID: 7, Name: rawName, Color: "#1677ff", Paths: []string{rawPath, "/中文/100% +?#.txt"}, CreatedAt: 10}
	if err := (recentBackend{db}).Save(recentRow); err != nil {
		t.Fatal(err)
	}
	if err := (favoritesBackend{db}).Save(favoriteRow); err != nil {
		t.Fatal(err)
	}
	if err := (tagsBackend{db}).Save(tagRow); err != nil {
		t.Fatal(err)
	}
	reopen()
	assertRows := func(expectedPath string, expectedName string, expectedTagPaths []string) {
		t.Helper()
		rows, err := (recentBackend{db}).GetAll()
		if err != nil || len(rows) != 1 || rows[0].ID != recentRow.ID || rows[0].UserID != 7 || rows[0].Path != expectedPath || rows[0].Name != expectedName || rows[0].PathUnverified {
			t.Fatalf("recent raw bytes lost after reopen: %#v %v", rows, err)
		}
		favorite, err := (favoritesBackend{db}).GetByID(7, favoriteRow.ID)
		if err != nil || favorite.Path != expectedPath || favorite.Name != expectedName || favorite.UserID != 7 {
			t.Fatalf("favorite raw bytes lost after reopen: %#v %v", favorite, err)
		}
		tag, err := (tagsBackend{db}).GetByID(7, tagRow.ID)
		if err != nil || tag.Name != expectedName || tag.UserID != 7 || len(tag.Paths) != len(expectedTagPaths) {
			t.Fatalf("tag raw bytes lost after reopen: %#v %v", tag, err)
		}
		for index, expected := range expectedTagPaths {
			if tag.Paths[index] != expected {
				t.Fatalf("tag path[%d] = %q, want raw %q", index, tag.Paths[index], expected)
			}
		}
		// The historical path index remains a raw-byte index, not display text.
		var indexed []*Favorite
		if err := db.Find("Path", expectedPath, &indexed); err != nil || len(indexed) != 1 || indexed[0].ID != favoriteRow.ID {
			t.Fatalf("favorite path index lost raw identity: %#v %v", indexed, err)
		}
	}
	assertRows(rawPath, rawName, tagRow.Paths)
	updatedPath := "/\xff\xfe/renamed% +?#.txt"
	updatedName := "\xff\xfe-renamed.txt"
	recentRow.Path = updatedPath
	recentRow.Name = updatedName
	recentRow.IsDir = false
	recentRow.AccessedAt = 11
	favoriteRow.Path = updatedPath
	favoriteRow.Name = updatedName
	tagRow.Name = updatedName
	tagRow.Paths = []string{updatedPath}
	if err := (recentBackend{db}).Update(recentRow); err != nil {
		t.Fatal(err)
	}
	if err := (favoritesBackend{db}).Update(favoriteRow); err != nil {
		t.Fatal(err)
	}
	if err := (tagsBackend{db}).Update(tagRow); err != nil {
		t.Fatal(err)
	}
	reopen()
	assertRows(updatedPath, updatedName, []string{updatedPath})
	if rows, err := (recentBackend{db}).GetAll(); err != nil || rows[0].IsDir {
		t.Fatalf("false IsDir was not persisted: %#v %v", rows, err)
	}
	finalPath := "/\xfe\xfd/final% +?#.txt"
	recentRow.Path = finalPath
	if err := (recentBackend{db}).Update(recentRow); err != nil {
		t.Fatal(err)
	}
	if err := (favoritesBackend{db}).UpdatePath(favoriteRow.ID, finalPath); err != nil {
		t.Fatal(err)
	}
	if err := (tagsBackend{db}).UpdatePaths(tagRow.ID, []string{finalPath}); err != nil {
		t.Fatal(err)
	}
	reopen()
	assertRows(finalPath, updatedName, []string{finalPath})
	if err := (tagsBackend{db}).UpdatePaths(tagRow.ID, []string{}); err != nil {
		t.Fatal(err)
	}
	reopen()
	assertRows(finalPath, updatedName, nil)
}

func TestLegacyRecentLostIdentityStaysUnverifiedWhileNewReplacementCharacterIsTrusted(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "legacy-recent-bytes.db")
	db, err := storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if db != nil {
			if err := db.Close(); err != nil {
				t.Error(err)
			}
		}
	})
	// Exact old JSON shape: no byte field. U+FFFD may represent bytes already lost.
	legacy := &recentRecord{ID: "legacy-lost", UserID: 7, Path: "/lost�.txt", Name: "lost�.txt", AccessedAt: 1}
	if err := db.Save(legacy); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&recentRecord{ID: "legacy-plain", UserID: 7, Path: "/plain.txt", Name: "plain.txt", AccessedAt: 2}); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db = nil
	db, err = storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	storage := recent.NewStorage(recentBackend{db})
	rows, err := storage.List(7, 100)
	if err != nil || len(rows) != 2 {
		t.Fatalf("legacy rows missing: %#v %v", rows, err)
	}
	for _, row := range rows {
		if row.ID == "legacy-plain" && row.PathUnverified {
			t.Fatal("valid old UTF-8 record was incorrectly rejected")
		}
		if row.ID == legacy.ID {
			if !row.PathUnverified {
				t.Fatal("old damaged path was promoted to trusted identity")
			}
			payload, err := json.Marshal(row)
			if err != nil {
				t.Fatal(err)
			}
			var wire map[string]interface{}
			if err := json.Unmarshal(payload, &wire); err != nil {
				t.Fatal(err)
			}
			if _, invented := wire["wirePath"]; invented || wire["pathVerified"] != false {
				t.Fatalf("invented raw path for old record: %s", payload)
			}
		}
	}
	created, err := storage.Record(7, legacy.Path, legacy.Name, false)
	if err != nil || created.ID == legacy.ID || created.PathUnverified {
		t.Fatalf("new real Unicode replacement-character path must be distinct and trusted: %#v %v", created, err)
	}
	payload, err := json.Marshal(created)
	if err != nil {
		t.Fatal(err)
	}
	var wire map[string]interface{}
	if err := json.Unmarshal(payload, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["pathVerified"] != true || wire["wirePath"] != files.EncodeWirePath(legacy.Path) {
		t.Fatalf("real new Unicode path lost identity: %s", payload)
	}
	if _, err := storage.RewritePathPrefix(legacy.Path, "/renamed.txt"); err != nil {
		t.Fatal(err)
	}
	if _, err := storage.RemovePathPrefix(legacy.Path); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db = nil
	db, err = storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	rows, err = (recentBackend{db}).GetAll()
	if err != nil || len(rows) != 3 {
		t.Fatalf("legacy history was erased: %#v %v", rows, err)
	}
	for _, row := range rows {
		if row.ID == legacy.ID && (row.Path != legacy.Path || !row.PathUnverified) {
			t.Fatalf("legacy row was rewritten as Unicode sibling: %#v", row)
		}
		if row.ID == created.ID && (row.Path != "/renamed.txt" || row.PathUnverified) {
			t.Fatalf("trusted new visit was not rewritten: %#v", row)
		}
	}
}

func TestMetadataLegacyFieldsAndOwnerClaimsPreserveBucketsAndRawBytes(t *testing.T) {
	databasePath := filepath.Join(t.TempDir(), "legacy-owner-bytes.db")
	db, err := storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if db != nil {
			if err := db.Close(); err != nil {
				t.Error(err)
			}
		}
	})
	if err := db.Save(&Favorite{ID: "legacy-favorite", UserID: 7, Path: "/plain.txt", Name: "plain.txt"}); err != nil {
		t.Fatal(err)
	}
	if err := db.Save(&Tag{ID: "legacy-tag", UserID: 7, Name: "old", Paths: []string{"/plain.txt"}}); err != nil {
		t.Fatal(err)
	}
	raw := "/\xff/\xfe.txt"
	if err := (favoritesBackend{db}).Save(&favorites.Favorite{ID: "raw-favorite", Path: raw, Name: "\xfe.txt"}); err != nil {
		t.Fatal(err)
	}
	if err := (tagsBackend{db}).Save(&tags.Tag{ID: "raw-tag", Name: "\xfe", Paths: []string{raw}}); err != nil {
		t.Fatal(err)
	}
	if err := (favoritesBackend{db}).ClaimLegacy(7); err != nil {
		t.Fatal(err)
	}
	if err := (tagsBackend{db}).ClaimLegacy(7); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db = nil
	db, err = storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	favoriteRows, err := (favoritesBackend{db}).GetAll(7)
	if err != nil || len(favoriteRows) != 2 {
		t.Fatalf("favorite bucket/owner changed: %#v %v", favoriteRows, err)
	}
	tagRows, err := (tagsBackend{db}).GetAll(7)
	if err != nil || len(tagRows) != 2 {
		t.Fatalf("tag bucket/owner changed: %#v %v", tagRows, err)
	}
	for _, row := range favoriteRows {
		if row.ID == "raw-favorite" && (row.Path != raw || row.Name != "\xfe.txt") {
			t.Fatalf("owner claim lost favorite bytes: %#v", row)
		}
		if row.ID == "legacy-favorite" && row.Path != "/plain.txt" {
			t.Fatalf("legacy favorite became unreadable: %#v", row)
		}
	}
	for _, row := range tagRows {
		if row.ID == "raw-tag" && (len(row.Paths) != 1 || row.Paths[0] != raw || row.Name != "\xfe") {
			t.Fatalf("owner claim lost tag bytes: %#v", row)
		}
		if row.ID == "legacy-tag" && (len(row.Paths) != 1 || row.Paths[0] != "/plain.txt") {
			t.Fatalf("legacy tag became unreadable: %#v", row)
		}
	}
}
