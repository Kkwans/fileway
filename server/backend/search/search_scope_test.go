package search

import (
	"context"
	"errors"
	"os"
	"sort"
	"testing"

	"github.com/spf13/afero"
)

type allowAllChecker struct{}

func (allowAllChecker) Check(string) bool { return true }

func TestSearchScopesAndExcludedDirectories(t *testing.T) {
	fs := afero.NewMemMapFs()
	for _, directory := range []string{"/root/sub", "/root/#recycle", "/root/.nas-file-browser-cache"} {
		if err := fs.MkdirAll(directory, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for _, filename := range []string{
		"/root/current.txt",
		"/root/sub/nested.txt",
		"/root/#recycle/deleted.txt",
		"/root/.nas-file-browser-cache/cached.txt",
	} {
		if err := afero.WriteFile(fs, filename, []byte("test"), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	excluded := map[string]struct{}{
		"#recycle":                {},
		".nas-file-browser-cache": {},
	}
	current, err := collectSearch(fs, Options{
		Scope: ScopeCurrent, ExcludedDirectories: excluded,
	})
	if err != nil {
		t.Fatal(err)
	}
	assertPaths(t, current, []string{"current.txt", "sub"})

	recursive, err := collectSearch(fs, Options{
		Scope: ScopeRecursive, ExcludedDirectories: excluded,
	})
	if err != nil {
		t.Fatal(err)
	}
	assertPaths(t, recursive, []string{"current.txt", "sub", "sub/nested.txt"})
}

func TestSearchLimitAndCancellation(t *testing.T) {
	fs := afero.NewMemMapFs()
	if err := fs.MkdirAll("/root", 0o755); err != nil {
		t.Fatal(err)
	}
	for _, filename := range []string{"a.txt", "b.txt", "c.txt"} {
		if err := afero.WriteFile(fs, "/root/"+filename, nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}

	paths := make([]string, 0, 2)
	err := Search(context.Background(), fs, "/root", "", allowAllChecker{}, Options{
		Scope: ScopeRecursive, Limit: 2,
	}, func(path string, _ os.FileInfo) error {
		paths = append(paths, path)
		return nil
	})
	if !errors.Is(err, ErrLimitReached) {
		t.Fatalf("expected ErrLimitReached, got %v", err)
	}
	if len(paths) != 2 {
		t.Fatalf("expected two partial results, got %d", len(paths))
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	err = Search(ctx, fs, "/root", "", allowAllChecker{}, Options{
		Scope: ScopeRecursive,
	}, func(string, os.FileInfo) error { return nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancellation, got %v", err)
	}
}

func collectSearch(fs afero.Fs, options Options) ([]string, error) {
	paths := make([]string, 0)
	err := Search(context.Background(), fs, "/root", "", allowAllChecker{}, options,
		func(path string, _ os.FileInfo) error {
			paths = append(paths, path)
			return nil
		})
	return paths, err
}

func assertPaths(t *testing.T, actual, expected []string) {
	t.Helper()
	sort.Strings(actual)
	sort.Strings(expected)
	if len(actual) != len(expected) {
		t.Fatalf("paths = %v, want %v", actual, expected)
	}
	for i := range expected {
		if actual[i] != expected[i] {
			t.Fatalf("paths = %v, want %v", actual, expected)
		}
	}
}
