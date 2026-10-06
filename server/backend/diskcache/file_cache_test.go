package diskcache

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/spf13/afero"
	"github.com/stretchr/testify/require"
)

func TestFileCache(t *testing.T) {
	ctx := context.Background()
	const (
		key            = "key"
		value          = "some text"
		newValue       = "new text"
		cacheRoot      = "/cache"
		cachedFilePath = "a/62/a62f2225bf70bfaccbc7f1ef2a397836717377de"
	)

	fs := afero.NewMemMapFs()
	cache := New(fs, "/cache")

	// store new key
	err := cache.Store(ctx, key, []byte(value))
	require.NoError(t, err)
	checkValue(ctx, t, fs, filepath.Join(cacheRoot, cachedFilePath), cache, key, value)

	// update existing key
	err = cache.Store(ctx, key, []byte(newValue))
	require.NoError(t, err)
	checkValue(ctx, t, fs, filepath.Join(cacheRoot, cachedFilePath), cache, key, newValue)

	// delete key
	err = cache.Delete(ctx, key)
	require.NoError(t, err)
	exists, err := afero.Exists(fs, filepath.Join(cacheRoot, cachedFilePath))
	require.NoError(t, err)
	require.False(t, exists)
}

func TestFileCacheCanceledStorePreservesCommittedValue(t *testing.T) {
	fs := afero.NewMemMapFs()
	cache := New(fs, "/cache")
	if err := cache.Store(context.Background(), "key", []byte("committed")); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := cache.Store(ctx, "key", []byte("partial")); !errors.Is(err, context.Canceled) {
		t.Fatalf("store error = %v, want context.Canceled", err)
	}
	value, exists, err := cache.Load(context.Background(), "key")
	if err != nil || !exists || string(value) != "committed" {
		t.Fatalf("value = %q, exists = %v, error = %v", value, exists, err)
	}
}

func TestFileCacheReleasesScopedLocks(t *testing.T) {
	cache := New(afero.NewMemMapFs(), "/cache")
	if err := cache.Store(context.Background(), "key", []byte("value")); err != nil {
		t.Fatal(err)
	}
	cache.scopedLocks.Lock()
	count := len(cache.scopedLocks.locks)
	cache.scopedLocks.Unlock()
	if count != 0 {
		t.Fatalf("retained scoped locks = %d, want 0", count)
	}
}

func TestBoundedFileCacheEvictsLeastRecentlyUsedEntry(t *testing.T) {
	fs := afero.NewMemMapFs()
	require.NoError(t, fs.MkdirAll("/cache", 0700))
	cache, err := NewBounded(fs, "/cache", 8)
	require.NoError(t, err)
	ctx := context.Background()

	require.NoError(t, cache.Store(ctx, "old", []byte("1234")))
	require.NoError(t, cache.Store(ctx, "recent", []byte("5678")))
	_, exists, err := cache.Load(ctx, "old")
	require.NoError(t, err)
	require.True(t, exists)
	require.NoError(t, cache.Store(ctx, "new", []byte("abcd")))

	_, exists, err = cache.Load(ctx, "recent")
	require.NoError(t, err)
	require.False(t, exists)
	for _, key := range []string{"old", "new"} {
		_, exists, err = cache.Load(ctx, key)
		require.NoError(t, err)
		require.True(t, exists, "expected %q to remain cached", key)
	}
}

func TestBoundedFileCachePrunesExistingEntriesAtStartup(t *testing.T) {
	fs := afero.NewMemMapFs()
	unbounded := New(fs, "/cache")
	require.NoError(t, unbounded.Store(context.Background(), "first", []byte("1234")))
	require.NoError(t, unbounded.Store(context.Background(), "second", []byte("5678")))

	bounded, err := NewBounded(fs, "/cache", 4)
	require.NoError(t, err)
	remaining := 0
	for _, key := range []string{"first", "second"} {
		_, exists, loadErr := bounded.Load(context.Background(), key)
		require.NoError(t, loadErr)
		if exists {
			remaining++
		}
	}
	require.Equal(t, 1, remaining)
}

func TestBoundedFileCacheSkipsEntryLargerThanLimit(t *testing.T) {
	fs := afero.NewMemMapFs()
	require.NoError(t, fs.MkdirAll("/cache", 0700))
	cache, err := NewBounded(fs, "/cache", 4)
	require.NoError(t, err)

	require.NoError(t, cache.Store(context.Background(), "large", []byte("12345")))
	_, exists, err := cache.Load(context.Background(), "large")
	require.NoError(t, err)
	require.False(t, exists)
}

func TestBoundedFileCacheOverwriteReplacesAccountedSize(t *testing.T) {
	fs := afero.NewMemMapFs()
	require.NoError(t, fs.MkdirAll("/cache", 0700))
	cache, err := NewBounded(fs, "/cache", 8)
	require.NoError(t, err)

	require.NoError(t, cache.Store(context.Background(), "same", []byte("1234")))
	require.NoError(t, cache.Store(context.Background(), "same", []byte("123456")))
	require.NoError(t, cache.Store(context.Background(), "other", []byte("78")))
	for _, key := range []string{"same", "other"} {
		_, exists, loadErr := cache.Load(context.Background(), key)
		require.NoError(t, loadErr)
		require.True(t, exists, "expected %q to remain cached", key)
	}
}

func TestBoundedFileCacheRemovesAbandonedTemporaryFiles(t *testing.T) {
	fs := afero.NewMemMapFs()
	require.NoError(t, fs.MkdirAll("/cache/a/62", 0700))
	require.NoError(t, afero.WriteFile(fs, "/cache/a/62/.cache-abandoned", []byte("partial"), 0600))

	_, err := NewBounded(fs, "/cache", 8)
	require.NoError(t, err)
	exists, err := afero.Exists(fs, "/cache/a/62/.cache-abandoned")
	require.NoError(t, err)
	require.False(t, exists)
}

func checkValue(ctx context.Context, t *testing.T, fs afero.Fs, fileFullPath string, cache *FileCache, key, wantValue string) {
	t.Helper()
	// check actual file content
	b, err := afero.ReadFile(fs, fileFullPath)
	require.NoError(t, err)
	require.Equal(t, wantValue, string(b))

	// check cache content
	b, ok, err := cache.Load(ctx, key)
	require.NoError(t, err)
	require.True(t, ok)
	require.Equal(t, wantValue, string(b))
}
