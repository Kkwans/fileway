package analysis

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"
)

type denyPrefix string

func (prefix denyPrefix) Check(filePath string) bool {
	return filePath != string(prefix) && !bytes.HasPrefix([]byte(filePath), []byte(string(prefix)+"/"))
}

func TestFindDuplicatesUsesSizeSampleAndFullSHA256(t *testing.T) {
	filesystem := afero.NewMemMapFs()
	if err := filesystem.MkdirAll("/scan/nested", 0o750); err != nil {
		t.Fatal(err)
	}
	first := append(bytes.Repeat([]byte("A"), 64*1024), bytes.Repeat([]byte("M"), 72*1024)...)
	first = append(first, bytes.Repeat([]byte("Z"), 64*1024)...)
	differentMiddle := append(bytes.Repeat([]byte("A"), 64*1024), bytes.Repeat([]byte("N"), 72*1024)...)
	differentMiddle = append(differentMiddle, bytes.Repeat([]byte("Z"), 64*1024)...)
	fixtures := map[string][]byte{
		"/scan/a.bin": first, "/scan/nested/a-copy.bin": first,
		"/scan/same-sample-different.bin": differentMiddle,
		"/scan/same-size-different.bin":   bytes.Repeat([]byte("Q"), len(first)),
		"/scan/empty-a":                   nil, "/scan/empty-b": nil,
	}
	for name, content := range fixtures {
		if err := afero.WriteFile(filesystem, name, content, 0o640); err != nil {
			t.Fatal(err)
		}
	}

	var last ScanProgress
	report, err := FindDuplicates(context.Background(), filesystem, []string{"/scan", "/scan/nested"}, nil, func(progress ScanProgress) error {
		last = progress
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if report.ScannedFiles != len(fixtures) {
		t.Fatalf("scanned files = %d report=%#v", report.ScannedFiles, report)
	}
	if report.DuplicateGroups != 2 || report.DuplicateFiles != 4 {
		t.Fatalf("duplicate summary = %#v", report)
	}
	if len(report.Groups) != 2 || report.Truncated {
		t.Fatalf("groups = %#v", report.Groups)
	}
	largeGroup := report.Groups[0]
	if largeGroup.TotalFiles != 2 || largeGroup.Files[0].Path != "/scan/a.bin" || largeGroup.Files[1].Path != "/scan/nested/a-copy.bin" {
		t.Fatalf("large exact-hash group = %#v", largeGroup)
	}
	if last.ProcessedItems != last.TotalItems || last.ProcessedBytes != last.TotalBytes {
		t.Fatalf("final progress = %#v", last)
	}
}

func TestFindDuplicatesHonorsRulesAndCancellation(t *testing.T) {
	filesystem := afero.NewMemMapFs()
	for _, directory := range []string{"/allowed", "/blocked"} {
		if err := filesystem.MkdirAll(directory, 0o750); err != nil {
			t.Fatal(err)
		}
		if err := afero.WriteFile(filesystem, directory+"/copy.txt", []byte("same"), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	report, err := FindDuplicates(context.Background(), filesystem, []string{"/"}, denyPrefix("/blocked"), nil)
	if err != nil {
		t.Fatal(err)
	}
	if report.ScannedFiles != 1 || report.DuplicateGroups != 0 {
		t.Fatalf("rule-filtered report = %#v", report)
	}

	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := FindDuplicates(canceled, filesystem, []string{"/"}, nil, nil); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled scan error = %v", err)
	}
}

func TestFindDuplicatesSkipsSymbolicLinks(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "source.txt"), []byte("same"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "source.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	report, err := FindDuplicates(context.Background(), filesystem, []string{"/"}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if report.ScannedFiles != 1 || report.SkippedCount != 1 || report.DuplicateGroups != 0 {
		t.Fatalf("symlink report = %#v", report)
	}
	if len(report.Skipped) != 1 || report.Skipped[0].Reason != "已跳过符号链接" {
		t.Fatalf("skipped details = %#v", report.Skipped)
	}
}
