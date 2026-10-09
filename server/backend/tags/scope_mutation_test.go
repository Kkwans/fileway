package tags

import "testing"

func TestUnknownWorkspaceDoesNotRewriteOrDeduplicateTag(t *testing.T) {
	backend := &memoryBackend{tags: []*Tag{{ID: "unknown", UserID: 0, Paths: []string{"/old.txt", "/old.txt"}}}}
	storage := NewStorage(backend)
	mutation, err := storage.RewritePathPrefix("/old.txt", "/new.txt", func(owner uint, path string) (string, bool) { return path, false })
	if err != nil {
		t.Fatal(err)
	}
	if len(mutation.UpdatedSnapshot()) != 0 {
		t.Fatal("unknown workspace unexpectedly mutated")
	}
	assertTagPaths(t, backend.tags, "unknown", []string{"/old.txt", "/old.txt"})
}
