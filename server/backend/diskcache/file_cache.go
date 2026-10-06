package diskcache

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/spf13/afero"
)

type FileCache struct {
	fs       afero.Fs
	maxBytes int64

	capacity struct {
		sync.Mutex
		entries    map[string]cacheEntry
		totalBytes int64
		lastAccess int64
	}

	scopedLocks struct {
		sync.Mutex
		locks map[string]*scopedLock
	}
}

type cacheEntry struct {
	path       string
	size       int64
	lastAccess int64
}

type scopedLock struct {
	sync.Mutex
	references int
}

func New(fs afero.Fs, root string) *FileCache {
	return &FileCache{
		fs: afero.NewBasePathFs(fs, root),
	}
}

// NewBounded creates a cache with an on-disk size ceiling. Existing entries
// are indexed once at startup and least-recently-used entries are evicted when
// a successful write crosses the limit.
func NewBounded(fs afero.Fs, root string, maxBytes int64) (*FileCache, error) {
	if maxBytes <= 0 {
		return nil, fmt.Errorf("cache limit must be positive")
	}
	cache := &FileCache{fs: afero.NewBasePathFs(fs, root), maxBytes: maxBytes}
	cache.capacity.entries = make(map[string]cacheEntry)
	if err := cache.loadEntries(); err != nil {
		return nil, err
	}
	cache.capacity.Lock()
	cache.pruneLocked("")
	cache.capacity.Unlock()
	return cache, nil
}

func (f *FileCache) Store(ctx context.Context, key string, value []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if f.maxBytes > 0 && int64(len(value)) > f.maxBytes {
		return nil
	}
	unlock := f.lockKey(key)
	defer unlock()

	fileName := f.getFileName(key)
	if err := f.fs.MkdirAll(filepath.Dir(fileName), 0700); err != nil {
		return err
	}

	temporary, err := afero.TempFile(f.fs, filepath.Dir(fileName), ".cache-*")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = f.fs.Remove(temporaryName)
		}
	}()

	if err := f.fs.Chmod(temporaryName, 0600); err != nil {
		return err
	}
	if _, err := temporary.Write(value); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := f.fs.Rename(temporaryName, fileName); err != nil {
		return err
	}
	committed = true
	f.recordStore(fileName, int64(len(value)))
	return nil
}

func (f *FileCache) Load(ctx context.Context, key string) (value []byte, exist bool, err error) {
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	r, ok, err := f.open(key)
	if err != nil || !ok {
		return nil, ok, err
	}
	defer func() {
		if closeErr := r.Close(); err == nil {
			err = closeErr
		}
	}()

	value, err = io.ReadAll(r)
	if err != nil {
		return nil, false, err
	}
	f.recordAccess(f.getFileName(key), int64(len(value)))
	return value, true, nil
}

func (f *FileCache) Delete(ctx context.Context, key string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	unlock := f.lockKey(key)
	defer unlock()
	if err := ctx.Err(); err != nil {
		return err
	}

	fileName := f.getFileName(key)
	if err := f.fs.Remove(fileName); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	f.forget(fileName)
	return nil
}

func (f *FileCache) loadEntries() error {
	return afero.Walk(f.fs, ".", func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() || !info.Mode().IsRegular() {
			return nil
		}
		if strings.HasPrefix(filepath.Base(path), ".cache-") {
			return f.fs.Remove(path)
		}
		access := info.ModTime().UnixNano()
		f.capacity.entries[path] = cacheEntry{path: path, size: info.Size(), lastAccess: access}
		f.capacity.totalBytes += info.Size()
		if access > f.capacity.lastAccess {
			f.capacity.lastAccess = access
		}
		return nil
	})
}

func (f *FileCache) recordStore(path string, size int64) {
	if f.maxBytes <= 0 {
		return
	}
	f.capacity.Lock()
	if previous, exists := f.capacity.entries[path]; exists {
		f.capacity.totalBytes -= previous.size
	}
	entry := cacheEntry{path: path, size: size, lastAccess: f.nextAccessLocked()}
	f.capacity.entries[path] = entry
	f.capacity.totalBytes += size
	f.pruneLocked(path)
	f.capacity.Unlock()
}

func (f *FileCache) recordAccess(path string, size int64) {
	if f.maxBytes <= 0 {
		return
	}
	f.capacity.Lock()
	entry, exists := f.capacity.entries[path]
	if !exists {
		entry = cacheEntry{path: path, size: size}
		f.capacity.totalBytes += size
	}
	entry.lastAccess = f.nextAccessLocked()
	f.capacity.entries[path] = entry
	f.pruneLocked(path)
	f.capacity.Unlock()
}

func (f *FileCache) forget(path string) {
	if f.maxBytes <= 0 {
		return
	}
	f.capacity.Lock()
	if entry, exists := f.capacity.entries[path]; exists {
		f.capacity.totalBytes -= entry.size
		delete(f.capacity.entries, path)
	}
	f.capacity.Unlock()
}

func (f *FileCache) nextAccessLocked() int64 {
	now := time.Now().UnixNano()
	if now <= f.capacity.lastAccess {
		now = f.capacity.lastAccess + 1
	}
	f.capacity.lastAccess = now
	return now
}

func (f *FileCache) pruneLocked(protectPath string) {
	if f.capacity.totalBytes <= f.maxBytes {
		return
	}
	candidates := make([]cacheEntry, 0, len(f.capacity.entries))
	for _, entry := range f.capacity.entries {
		if entry.path != protectPath {
			candidates = append(candidates, entry)
		}
	}
	sort.Slice(candidates, func(left, right int) bool {
		if candidates[left].lastAccess == candidates[right].lastAccess {
			return candidates[left].path < candidates[right].path
		}
		return candidates[left].lastAccess < candidates[right].lastAccess
	})
	for _, candidate := range candidates {
		if f.capacity.totalBytes <= f.maxBytes {
			break
		}
		if err := f.fs.Remove(candidate.path); err != nil && !errors.Is(err, os.ErrNotExist) {
			continue
		}
		f.capacity.totalBytes -= candidate.size
		delete(f.capacity.entries, candidate.path)
	}
}

func (f *FileCache) open(key string) (afero.File, bool, error) {
	fileName := f.getFileName(key)
	file, err := f.fs.Open(fileName)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, false, nil
		}
		return nil, false, err
	}

	return file, true, nil
}

// lockKey serializes writes for one cache key and removes the lock entry after
// the last user leaves, preventing long-running servers from retaining one
// mutex for every preview ever requested.
func (f *FileCache) lockKey(key string) func() {
	f.scopedLocks.Lock()
	if f.scopedLocks.locks == nil {
		f.scopedLocks.locks = make(map[string]*scopedLock)
	}
	lock := f.scopedLocks.locks[key]
	if lock == nil {
		lock = &scopedLock{}
		f.scopedLocks.locks[key] = lock
	}
	lock.references++
	f.scopedLocks.Unlock()

	lock.Lock()
	return func() {
		lock.Unlock()
		f.scopedLocks.Lock()
		lock.references--
		if lock.references == 0 {
			delete(f.scopedLocks.locks, key)
		}
		f.scopedLocks.Unlock()
	}
}

func (f *FileCache) getFileName(key string) string {
	hasher := sha1.New()
	_, _ = hasher.Write([]byte(key))
	hash := hex.EncodeToString(hasher.Sum(nil))
	return fmt.Sprintf("%s/%s/%s", hash[:1], hash[1:3], hash)
}
