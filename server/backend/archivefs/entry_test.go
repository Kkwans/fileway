package archivefs

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"testing"
	"time"

	"github.com/mholt/archives"
	"github.com/spf13/afero"
)

func entryInput(t *testing.T, filesystem afero.Fs, source, selected string) EntryInput {
	t.Helper()
	info, err := filesystem.Stat(source)
	if err != nil {
		t.Fatal(err)
	}
	return EntryInput{UserID: 1, Namespace: "owned-scope", Filesystem: filesystem, ArchivePath: source, EntryPath: selected, SourceSize: info.Size(), SourceModified: info.ModTime().UnixMilli()}
}
func waitPrepared(t *testing.T, cache *EntryCache, input EntryInput, id, state string) EntryStatus {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		_, value, err := cache.Lookup(input.UserID, input.Namespace, id)
		if err != nil {
			t.Fatal(err)
		}
		if value.State == state {
			return value
		}
		if value.State == "failed" && state != "failed" {
			t.Fatalf("prepare failed: %#v", value)
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("preparation did not settle")
	return EntryStatus{}
}
func TestMaterializeSingleEntryAcrossSupportedFormatsWithoutUserWrites(t *testing.T) {
	for _, item := range []struct {
		name   string
		format archives.Archiver
	}{{"zip", archives.Zip{}}, {"tar", archives.Tar{}}, {"tar.gz", compressedTar(archives.Gz{})}, {"tar.bz2", compressedTar(archives.Bz2{})}, {"tar.xz", compressedTar(archives.Xz{})}, {"tar.zst", compressedTar(archives.Zstd{})}} {
		t.Run(item.name, func(t *testing.T) {
			filesystem := testFilesystem(t)
			source := "/bundle." + item.name
			writeArchive(t, filesystem, source, item.format, map[string]string{"folder/keep.txt": "selected", "skip.txt": "unselected"})
			var output bytes.Buffer
			entry, err := MaterializeEntry(context.Background(), entryInput(t, filesystem, source, "folder/keep.txt"), &output, Limits{}, nil, nil)
			if err != nil || entry.Size != 8 || output.String() != "selected" {
				t.Fatalf("entry=%#v output=%q err=%v", entry, output.String(), err)
			}
			rows, err := afero.ReadDir(filesystem, "/")
			if err != nil || len(rows) != 1 {
				t.Fatalf("user Fs changed: %#v %v", rows, err)
			}
		})
	}
}
func TestMaterializeRejectsDuplicatesLinksSlipAndSourceChanges(t *testing.T) {
	filesystem := testFilesystem(t)
	for _, attack := range []string{"duplicate", "symlink"} {
		var buffer bytes.Buffer
		writer := zip.NewWriter(&buffer)
		header := &zip.FileHeader{Name: "keep.txt", Method: zip.Store}
		header.SetMode(0o600)
		if attack == "symlink" {
			header.SetMode(os.ModeSymlink | 0o777)
		}
		entry, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = entry.Write([]byte("owned"))
		if attack == "duplicate" {
			second, err := writer.Create("keep.txt")
			if err != nil {
				t.Fatal(err)
			}
			_, _ = second.Write([]byte("different"))
		}
		if err := writer.Close(); err != nil {
			t.Fatal(err)
		}
		if err := afero.WriteFile(filesystem, "/attack.zip", buffer.Bytes(), 0o600); err != nil {
			t.Fatal(err)
		}
		var output bytes.Buffer
		if _, err := MaterializeEntry(context.Background(), entryInput(t, filesystem, "/attack.zip", "keep.txt"), &output, Limits{}, nil, nil); !errors.Is(err, ErrUnsafeEntry) {
			t.Fatalf("%s accepted: %v", attack, err)
		}
	}
	writeArchive(t, filesystem, "/safe.tar", archives.Tar{}, map[string]string{"keep.txt": "owned"})
	input := entryInput(t, filesystem, "/safe.tar", "../outside")
	if _, err := MaterializeEntry(context.Background(), input, io.Discard, Limits{}, nil, nil); !errors.Is(err, ErrUnsafeEntry) {
		t.Fatal("ZipSlip accepted", err)
	}
	input.EntryPath = "keep.txt"
	input.SourceSize++
	if _, err := MaterializeEntry(context.Background(), input, io.Discard, Limits{}, nil, nil); !errors.Is(err, ErrArchiveChanged) {
		t.Fatal("stale source accepted", err)
	}
}
func TestEntryCacheDeduplicatesPinsTTLAndKeepsBudgetCancelCleanupPrivate(t *testing.T) {
	filesystem := testFilesystem(t)
	writeArchive(t, filesystem, "/bundle.tar", archives.Tar{}, map[string]string{"keep.txt": "0123456789"})
	input := entryInput(t, filesystem, "/bundle.tar", "keep.txt")
	work := t.TempDir()
	cache, err := NewEntryCache(EntryCacheConfig{WorkDir: work, MaxBytes: 64, TTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cache.Close() }()
	started, err := cache.Prepare(input)
	if err != nil {
		t.Fatal(err)
	}
	waitPrepared(t, cache, input, started.ID, "ready")
	again, err := cache.Prepare(input)
	if err != nil || again.ID != started.ID {
		t.Fatal("same source not stable", again, err)
	}
	if _, _, err := cache.Lookup(2, input.Namespace, started.ID); !errors.Is(err, ErrEntryForbidden) {
		t.Fatal("cross-account artifact accepted", err)
	}
	file, _, err := cache.Open(input.UserID, input.Namespace, started.ID)
	if err != nil {
		t.Fatal(err)
	}
	cache.sweep(time.Now().Add(2 * time.Minute))
	if content, err := io.ReadAll(file); err != nil || string(content) != "0123456789" {
		t.Fatal("pinned artifact removed", err)
	}
	_ = file.Close()
	cache.sweep(time.Now().Add(2 * time.Minute))
	if _, _, err := cache.Lookup(input.UserID, input.Namespace, started.ID); !errors.Is(err, ErrEntryNotFound) {
		t.Fatal("expired artifact retained", err)
	}
	cache.slots <- struct{}{}
	queued, err := cache.Prepare(input)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := cache.Cancel(input.UserID, input.Namespace, queued.ID); err != nil {
		t.Fatal(err)
	}
	waitPrepared(t, cache, input, queued.ID, "canceled")
	<-cache.slots
	budget, err := NewEntryCache(EntryCacheConfig{WorkDir: work, MaxBytes: 4})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = budget.Close() }()
	failed, err := budget.Prepare(input)
	if err != nil {
		t.Fatal(err)
	}
	waitPrepared(t, budget, input, failed.ID, "failed")
	if budget.used != 0 {
		t.Fatal("failed partial retained budget", budget.used)
	}
	if _, _, err := budget.Open(input.UserID, input.Namespace, failed.ID); !errors.Is(err, ErrEntryNotReady) {
		t.Fatal("failed partial published", err)
	}
}
