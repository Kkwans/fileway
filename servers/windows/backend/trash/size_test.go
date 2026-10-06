package trash_test

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/spf13/afero"
)

func TestDirectorySizePersistsAndDoesNotFollowLinks(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, "/docs/a", "abc")
	h.writeFile(t, "/docs/sub/b", "12345")
	h.writeFile(t, "/outside", "do not count")
	if err := h.fs.(afero.Symlinker).SymlinkIfPossible("/outside", "/docs/sub/link"); err != nil {
		t.Fatal(err)
	}
	item, err := h.service.Move(testUserID, testUserName, "/docs")
	if err != nil {
		t.Fatal(err)
	}
	if item.Size != 0 || item.Public().SizeState != trash.SizeUnknown {
		t.Fatal("directory inode size exposed as accurate bytes")
	}
	ctx, release, err := h.storage.Trash.ReserveSize(testUserID, item.ID, false, "size-1")
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if _, _, err := h.storage.Trash.ReserveSize(testUserID, item.ID, false, "size-2"); !errors.Is(err, trash.ErrUnavailable) {
		t.Fatalf("duplicate scan accepted: %v", err)
	}
	measured, err := h.service.MeasureSize(ctx, testUserID, item.ID, "size-1", nil)
	if err != nil {
		t.Fatal(err)
	}
	if measured.Size != 8 || measured.SizeState != trash.SizeAccurate {
		t.Fatalf("bad result: %+v", measured)
	}
	stored, _ := h.storage.Trash.Get(testUserID, item.ID, false)
	if stored.Size != 8 || stored.SizeState != trash.SizeAccurate {
		t.Fatalf("not persisted: %+v", stored)
	}
}

func TestDirectorySizeDoesNotResurrectRestoredOrDeletedRecord(t *testing.T) {
	for _, restore := range []bool{true, false} {
		t.Run(map[bool]string{true: "restore", false: "delete"}[restore], func(t *testing.T) {
			h := newServiceHarness(t)
			h.writeFile(t, "/docs/a", "abc")
			item, err := h.service.Move(testUserID, testUserName, "/docs")
			if err != nil {
				t.Fatal(err)
			}
			ctx, release, err := h.storage.Trash.ReserveSize(testUserID, item.ID, false, "size")
			if err != nil {
				t.Fatal(err)
			}
			defer release()
			called := false
			_, _ = h.service.MeasureSize(ctx, testUserID, item.ID, "size", func(int, int64) error {
				if called {
					return nil
				}
				called = true
				if restore {
					_, err = h.service.Restore(testUserID, item.ID, false, trash.ConflictFail)
				} else {
					err = h.service.DeletePermanent(testUserID, item.ID, false)
				}
				return err
			})
			if !called || err != nil {
				t.Fatalf("mutation did not run: %v", err)
			}
			if _, err = h.storage.Trash.Get(testUserID, item.ID, false); !errors.Is(err, trash.ErrNotExist) {
				t.Fatalf("record resurrected: %v", err)
			}
			if restore {
				if _, err = h.fs.Stat("/docs/a"); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}

func TestDirectorySizeCancellationAndRestartRequireExplicitRetry(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, "/docs/a", "abc")
	item, err := h.service.Move(testUserID, testUserName, "/docs")
	if err != nil {
		t.Fatal(err)
	}
	ctx, release, err := h.storage.Trash.ReserveSize(testUserID, item.ID, false, "size")
	if err != nil {
		t.Fatal(err)
	}
	release()
	result, err := h.service.MeasureSize(ctx, testUserID, item.ID, "size", nil)
	if !errors.Is(err, context.Canceled) || result.SizeState != trash.SizeIncomplete {
		t.Fatalf("canceled: %+v %v", result, err)
	}
	_, release, err = h.storage.Trash.ReserveSize(testUserID, item.ID, false, "retry")
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	if err = h.storage.Trash.RecoverSizes(); err != nil {
		t.Fatal(err)
	}
	result, _ = h.storage.Trash.Get(testUserID, item.ID, false)
	if result.SizeState != trash.SizeIncomplete {
		t.Fatal("restart left calculating forever")
	}
}

func TestDirectorySizeCanPersistZeroAfterRecalculation(t *testing.T) {
	h := newServiceHarness(t)
	h.writeFile(t, "/docs/a", "abc")
	item, err := h.service.Move(testUserID, testUserName, "/docs")
	if err != nil {
		t.Fatal(err)
	}
	for index, expected := range []int64{3, 0} {
		ctx, release, err := h.storage.Trash.ReserveSize(testUserID, item.ID, false, "size")
		if err != nil {
			t.Fatal(err)
		}
		result, err := h.service.MeasureSize(ctx, testUserID, item.ID, "size", nil)
		release()
		if err != nil || result.Size != expected || result.SizeState != trash.SizeAccurate {
			t.Fatalf("scan %d: %+v %v", index, result, err)
		}
		stored, _ := h.storage.Trash.Get(testUserID, item.ID, false)
		if stored.Size != expected {
			t.Fatal("zero value lost in storage update")
		}
		if index == 0 {
			if err = h.fs.Remove(item.StoredPath + "/a"); err != nil && !os.IsNotExist(err) {
				t.Fatal(err)
			}
		}
	}
}
