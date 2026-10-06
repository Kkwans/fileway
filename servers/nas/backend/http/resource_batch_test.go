package fbhttp

import (
	"net/http"
	"os"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
)

func TestBatchResourceValidation(t *testing.T) {
	if validateBatchResourcePaths(nil) == nil {
		t.Fatal("empty batch should be rejected")
	}
	if validateBatchResourcePaths(make([]string, maxBatchResourcePaths)) != nil {
		t.Fatal("maximum-sized batch should be accepted")
	}
	if validateBatchResourcePaths(make([]string, maxBatchResourcePaths+1)) == nil {
		t.Fatal("oversized batch should be rejected")
	}
}

func TestResolveBatchResourcesPreservesOrderAndPartialErrors(t *testing.T) {
	requested := []string{"//docs//first.txt", "/missing.txt", "/docs/last.txt"}
	results := resolveBatchResources(requested, func(path string) (*files.FileInfo, error) {
		if path == "/missing.txt" {
			return nil, os.ErrNotExist
		}
		return &files.FileInfo{Path: path, Name: path, Extension: ".txt"}, nil
	})

	if len(results) != len(requested) {
		t.Fatalf("result count = %d", len(results))
	}
	if results[0].Path != "/docs/first.txt" || results[2].Path != "/docs/last.txt" {
		t.Fatalf("normalized order was not preserved: %#v", results)
	}
	if results[0].Status != http.StatusOK || results[0].Item == nil {
		t.Fatalf("first result = %#v", results[0])
	}
	if results[1].Status != http.StatusNotFound || results[1].Error == "" || results[1].Item != nil {
		t.Fatalf("partial error = %#v", results[1])
	}
}
