package analysis

import (
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
)

func TestRecommendDuplicateKeeperRequiresUniqueEarliestBirthTime(t *testing.T) {
	safe := &files.Identity{Inode: 1, Links: 1, Mode: 0o100000}
	early := time.Unix(100, 1)
	later := time.Unix(100, 2)
	for _, test := range []struct {
		name         string
		files        []DuplicateFile
		path, reason string
	}{
		{"nanosecond precision", []DuplicateFile{{Path: "later", Identity: safe, Created: &later}, {Path: "oldest", Identity: safe, Created: &early}}, "oldest", "oldest-created"},
		{"any missing", []DuplicateFile{{Path: "oldest", Identity: safe, Created: &early}, {Path: "unknown", Identity: safe}}, "", "missing-created"},
		{"tied earliest", []DuplicateFile{{Path: "a", Identity: safe, Created: &early}, {Path: "b", Identity: safe, Created: &early}}, "", "tied-created"},
		{"unsafe identity", []DuplicateFile{{Path: "a", Identity: &files.Identity{Inode: 1, Links: 2, Mode: 0o100000}, Created: &early}, {Path: "b", Identity: safe, Created: &later}}, "", "unsafe-identity"},
		{"symbolic link", []DuplicateFile{{Path: "a", Identity: &files.Identity{Inode: 1, Links: 1, Mode: 0o120000}, Created: &early}, {Path: "b", Identity: safe, Created: &later}}, "", "unsafe-identity"},
		{"later tie irrelevant", []DuplicateFile{{Path: "a", Identity: safe, Created: &later}, {Path: "b", Identity: safe, Created: &later}, {Path: "oldest", Identity: safe, Created: &early}}, "oldest", "oldest-created"},
	} {
		t.Run(test.name, func(t *testing.T) {
			path, reason := RecommendDuplicateKeeper(test.files)
			if path != test.path || reason != test.reason {
				t.Fatalf("got %q %q", path, reason)
			}
		})
	}
}
