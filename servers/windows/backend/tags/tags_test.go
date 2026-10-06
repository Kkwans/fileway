package tags

import (
	"errors"
	"testing"
)

func TestStorageKeepsTagsIsolatedByUser(t *testing.T) {
	backend := &memoryBackend{tags: []*Tag{
		{ID: "admin", UserID: 1, Name: "工作"},
		{ID: "guest", UserID: 2, Name: "私密"},
	}}
	storage := NewStorage(backend)

	tags, err := storage.GetAll(1)
	if err != nil {
		t.Fatalf("读取标签失败: %v", err)
	}
	if len(tags) != 1 || tags[0].ID != "admin" {
		t.Fatalf("用户 1 不应读取其他用户的标签: %#v", tags)
	}
}

func TestStorageRewritesAndRemovesPathPrefixesForEveryUser(t *testing.T) {
	backend := &memoryBackend{tags: []*Tag{
		{ID: "admin", UserID: 1, Name: "工作", Paths: []string{"/docs/report.md", "/archive/report.md"}},
		{ID: "guest", UserID: 2, Name: "共享", Paths: []string{"/docs/team", "/docs-old/keep.md", "/Docs/case.md"}},
	}}
	storage := NewStorage(backend)

	mutation, err := storage.RewritePathPrefix("/docs", "/archive")
	if err != nil {
		t.Fatalf("重写标签路径失败: %v", err)
	}
	assertTagPaths(t, backend.tags, "admin", []string{"/archive/report.md"})
	assertTagPaths(t, backend.tags, "guest", []string{"/archive/team", "/docs-old/keep.md", "/Docs/case.md"})

	if err := storage.RestorePathMutation(mutation); err != nil {
		t.Fatalf("回滚标签路径失败: %v", err)
	}
	assertTagPaths(t, backend.tags, "admin", []string{"/docs/report.md", "/archive/report.md"})

	if _, err := storage.RemovePathPrefix("/docs"); err != nil {
		t.Fatalf("清理标签路径失败: %v", err)
	}
	assertTagPaths(t, backend.tags, "admin", []string{"/archive/report.md"})
	assertTagPaths(t, backend.tags, "guest", []string{"/docs-old/keep.md", "/Docs/case.md"})
}

func TestUpdatedSnapshotIsIndependentAndRestorable(t *testing.T) {
	backend := &failingTagBackend{
		memoryBackend: &memoryBackend{
			tags: []*Tag{{ID: "tag", UserID: 7, Paths: []string{"/docs/file.md", "/other"}}},
		},
	}
	storage := NewStorage(backend)
	mutation, err := storage.RemovePathPrefix("/docs")
	if err != nil {
		t.Fatal(err)
	}
	snapshot := mutation.UpdatedSnapshot()
	if len(snapshot) != 1 {
		t.Fatalf("snapshot = %#v", snapshot)
	}
	snapshot[0].Paths[0] = "/changed"
	if mutation.UpdatedSnapshot()[0].Paths[0] != "/docs/file.md" {
		t.Fatal("snapshot mutated the original path mutation")
	}
	if err := storage.RestoreUpdatedSnapshot(mutation.UpdatedSnapshot()); err != nil {
		t.Fatal(err)
	}
	if got := backend.tags[0].Paths; len(got) != 2 || got[0] != "/docs/file.md" || got[1] != "/other" {
		t.Fatalf("restored paths = %#v", got)
	}
}

func TestPathMutationCompensatesPartialBackendFailures(t *testing.T) {
	original := []*Tag{
		{ID: "first", UserID: 1, Paths: []string{"/docs/first.md"}},
		{ID: "second", UserID: 2, Paths: []string{"/docs/second.md"}},
	}

	for _, operation := range []struct {
		name string
		run  func(*Storage) error
	}{
		{name: "rewrite", run: func(storage *Storage) error {
			_, err := storage.RewritePathPrefix("/docs", "/archive")
			return err
		}},
		{name: "remove", run: func(storage *Storage) error {
			_, err := storage.RemovePathPrefix("/docs")
			return err
		}},
	} {
		t.Run(operation.name, func(t *testing.T) {
			backend := &failingTagBackend{
				memoryBackend: &memoryBackend{tags: cloneTags(original)},
				failUpdateAt:  2,
			}
			if err := operation.run(NewStorage(backend)); err == nil {
				t.Fatal("path mutation should report the injected backend failure")
			}
			assertTagPaths(t, backend.tags, "first", []string{"/docs/first.md"})
			assertTagPaths(t, backend.tags, "second", []string{"/docs/second.md"})
		})
	}
}

func cloneTags(source []*Tag) []*Tag {
	cloned := make([]*Tag, 0, len(source))
	for _, tag := range source {
		copy := cloneTag(*tag)
		cloned = append(cloned, &copy)
	}
	return cloned
}

type failingTagBackend struct {
	*memoryBackend
	updateCalls  int
	failUpdateAt int
}

func (m *failingTagBackend) UpdatePaths(id string, paths []string) error {
	m.updateCalls++
	if m.updateCalls == m.failUpdateAt {
		return errors.New("injected tag update failure")
	}
	return m.memoryBackend.UpdatePaths(id, paths)
}

func assertTagPaths(t *testing.T, tags []*Tag, id string, want []string) {
	t.Helper()
	for _, tag := range tags {
		if tag.ID == id {
			if len(tag.Paths) != len(want) {
				t.Fatalf("标签 %s 路径为 %#v，期望 %#v", id, tag.Paths, want)
			}
			for i := range want {
				if tag.Paths[i] != want[i] {
					t.Fatalf("标签 %s 路径为 %#v，期望 %#v", id, tag.Paths, want)
				}
			}
			return
		}
	}
	t.Fatalf("未找到标签 %s", id)
}

type memoryBackend struct{ tags []*Tag }

func (m *memoryBackend) GetAll(userID uint) ([]*Tag, error) {
	result := make([]*Tag, 0)
	for _, tag := range m.tags {
		if tag.UserID == userID {
			result = append(result, tag)
		}
	}
	return result, nil
}
func (m *memoryBackend) GetAllForPathMutation() ([]*Tag, error) {
	return append([]*Tag(nil), m.tags...), nil
}
func (m *memoryBackend) GetByID(userID uint, id string) (*Tag, error) {
	for _, tag := range m.tags {
		if tag.ID == id && tag.UserID == userID {
			return tag, nil
		}
	}
	return nil, ErrNotExist
}
func (m *memoryBackend) Save(tag *Tag) error {
	copy := cloneTag(*tag)
	m.tags = append(m.tags, &copy)
	return nil
}
func (m *memoryBackend) Update(tag *Tag) error {
	for i, current := range m.tags {
		if current.ID == tag.ID {
			copy := cloneTag(*tag)
			m.tags[i] = &copy
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) UpdatePaths(id string, paths []string) error {
	for _, tag := range m.tags {
		if tag.ID == id {
			tag.Paths = append([]string(nil), paths...)
			return nil
		}
	}
	return ErrNotExist
}
func (m *memoryBackend) Delete(string) error    { return nil }
func (m *memoryBackend) ClaimLegacy(uint) error { return nil }
