package analysis

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"
)

func TestAnalyzeStorageAggregatesSelectedScopesAndRanksResults(t *testing.T) {
	filesystem := afero.NewMemMapFs()
	for _, directory := range []string{"/scan", "/scan/docs", "/scan/media", "/other"} {
		if err := filesystem.MkdirAll(directory, 0o750); err != nil {
			t.Fatal(err)
		}
	}
	fixtures := map[string]int{
		"/scan/docs/readme.md":  5,
		"/scan/media/movie.bin": 21,
		"/scan/media/photo.bin": 13,
		"/other/outside.bin":    50,
	}
	for name, size := range fixtures {
		if err := afero.WriteFile(filesystem, name, bytes.Repeat([]byte("x"), size), 0o640); err != nil {
			t.Fatal(err)
		}
	}

	var progress ScanProgress
	report, err := AnalyzeStorage(context.Background(), filesystem, []string{"/scan"}, nil, func(next ScanProgress) error {
		progress = next
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if report.ScannedFiles != 3 || report.ScannedBytes != 39 || report.ScannedDirectories != 3 {
		t.Fatalf("summary = %#v", report)
	}
	if len(report.Scopes) != 1 || report.Scopes[0].Path != "/scan" || report.Scopes[0].Files != 3 || report.Scopes[0].Bytes != 39 {
		t.Fatalf("scope summary = %#v", report.Scopes)
	}
	if len(report.LargestFiles) != 3 || report.LargestFiles[0].Path != "/scan/media/movie.bin" || report.LargestFiles[2].Path != "/scan/docs/readme.md" {
		t.Fatalf("largest files = %#v", report.LargestFiles)
	}
	if len(report.LargestDirectories) != 3 || report.LargestDirectories[0].Path != "/scan" || report.LargestDirectories[0].Bytes != 39 || report.LargestDirectories[1].Path != "/scan/media" || report.LargestDirectories[1].Bytes != 34 {
		t.Fatalf("largest directories = %#v", report.LargestDirectories)
	}
	if progress.ProcessedItems != report.ScannedFiles+report.ScannedDirectories || progress.ProcessedBytes != report.ScannedBytes {
		t.Fatalf("final progress = %#v", progress)
	}
}

func TestAnalyzeStorageHonorsRulesCancellationAndSymlinks(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "allowed"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "blocked"), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "allowed", "file.txt"), []byte("value"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "blocked", "secret.txt"), []byte("hidden"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "allowed", "file.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), root)
	report, err := AnalyzeStorage(context.Background(), filesystem, []string{"/"}, denyPrefix("/blocked"), nil)
	if err != nil {
		t.Fatal(err)
	}
	if report.ScannedFiles != 1 || report.ScannedBytes != 5 || report.SkippedCount != 1 {
		t.Fatalf("filtered report = %#v", report)
	}

	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := AnalyzeStorage(canceled, filesystem, []string{"/"}, nil, nil); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled scan error = %v", err)
	}
}

func TestAnalyzeStorageBoundsLargestFileResults(t *testing.T) {
	filesystem := afero.NewMemMapFs()
	if err := filesystem.MkdirAll("/scan", 0o750); err != nil {
		t.Fatal(err)
	}
	for index := 0; index < DefaultStorageResultLimit+5; index++ {
		name := filepath.Join("/scan", fmt.Sprintf("%03d.bin", index))
		if err := afero.WriteFile(filesystem, name, bytes.Repeat([]byte("x"), index+1), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	report, err := AnalyzeStorage(context.Background(), filesystem, []string{"/scan"}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(report.LargestFiles) != DefaultStorageResultLimit || !report.Truncated {
		t.Fatalf("bounded report = %#v", report)
	}
	if report.LargestFiles[0].Size != DefaultStorageResultLimit+5 || report.LargestFiles[len(report.LargestFiles)-1].Size != 6 {
		t.Fatalf("bounded largest files = %#v", report.LargestFiles)
	}
}
