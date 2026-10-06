package transport

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

func TestCachedRangeReuseAndAuthorization(t *testing.T) {
	payload := bytes.Repeat([]byte("abcdefgh"), int(cacheChunkSize/4))
	var denied atomic.Bool
	var chunks atomic.Int64
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if denied.Load() {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if r.Header.Get("Range") == "bytes=0-2097151" {
			chunks.Add(1)
		}
		w.Header().Set("ETag", "\"movie-one\"")
		http.ServeContent(w, r, "movie.mkv", time.Unix(1000, 0), bytes.NewReader(payload))
	}))
	defer source.Close()
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err = b.ConfigureCache(CacheConfig{Directory: t.TempDir(), MaxBytes: 8 << 20, Hours: 24}); err != nil {
		t.Fatal(err)
	}
	id, _ := b.Open(source.URL, "account-token", nil)
	value, _ := b.LeaseCached(id, "/api/raw/movie.mkv", "profile/account/file-identity")
	for attempt := 0; attempt < 2; attempt++ {
		req, _ := http.NewRequest("GET", value, nil)
		req.Header.Set("Range", "bytes=123-456")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		data, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 206 || res.Header.Get("Content-Range") != "bytes 123-456/"+strconv.Itoa(len(payload)) || !bytes.Equal(data, payload[123:457]) {
			t.Fatalf("range changed: %d %q %d", res.StatusCode, res.Header.Get("Content-Range"), len(data))
		}
	}
	if chunks.Load() != 1 {
		t.Fatalf("cached chunk fetched %d times", chunks.Load())
	}
	denied.Store(true)
	req, _ := http.NewRequest("GET", value, nil)
	req.Header.Set("Range", "bytes=123-456")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatalf("cache bypassed authorization: %d", res.StatusCode)
	}
}
func TestCacheCleanupProtectsActiveLeaseAndCapacityLRU(t *testing.T) {
	b, _ := New()
	defer b.Close()
	dir := t.TempDir()
	if err := b.ConfigureCache(CacheConfig{Directory: dir, MaxBytes: 10, Hours: 24}); err != nil {
		t.Fatal(err)
	}
	id, _ := b.Open("https://example.invalid", "", nil)
	value, _ := b.LeaseCached(id, "/api/raw/a", "identity")
	_, active := b.cacheState()
	var group string
	for key := range active {
		group = key
	}
	b.cache.write(group, "first", []byte("123456"))
	time.Sleep(time.Millisecond * 5)
	b.cache.write(group, "second", []byte("abcdef"))
	stats, _ := b.CleanCache(true)
	if stats.Bytes != 6 || stats.Protected != 1 {
		t.Fatalf("active protection or capacity failed: %+v", stats)
	}
	b.Revoke(value)
	stats, err := b.CleanCache(true)
	if err != nil || stats.Bytes != 0 {
		t.Fatalf("inactive clear failed: %+v %v", stats, err)
	}
	b.cache.write("inactive", "third", []byte("abcd"))
	old := time.Now().Add(-25 * time.Hour)
	_ = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			_ = os.Chtimes(path, old, old)
		}
		return nil
	})
	stats, err = b.CleanCache(false)
	if err != nil || stats.Bytes != 0 {
		t.Fatalf("expiry failed: %+v %v", stats, err)
	}
	if err = b.ConfigureCache(CacheConfig{Directory: dir, MaxBytes: 0, Hours: 24}); err != nil || b.cache.enabled() {
		t.Fatal("zero did not disable cache")
	}
}
