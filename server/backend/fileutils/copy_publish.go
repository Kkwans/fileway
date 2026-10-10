package fileutils

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sync"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/spf13/afero"
	"github.com/spf13/afero/mem"
)

// Also serialize this caller's fallback publishers on non-native afero
// filesystems; native filesystems use PublishUpload's atomic OS no-replace.
var copyPublicationMu sync.Mutex

func copyFilePublished(afs afero.Fs, source, dest string, fileMode, dirMode fs.FileMode, overwrite bool) (err error) {
	if filepath.Clean(source) == filepath.Clean(dest) {
		return os.ErrInvalid
	}
	src, err := afs.Open(source)
	if err != nil {
		return err
	}
	srcOpen := true
	defer func() {
		if srcOpen {
			if closeErr := src.Close(); err == nil {
				err = closeErr
			}
		}
	}()
	info, err := src.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return os.ErrInvalid
	}
	if target, statErr := afs.Stat(dest); statErr == nil {
		if os.SameFile(info, target) {
			return os.ErrInvalid
		}
		if !overwrite {
			return os.ErrExist
		}
	} else if !os.IsNotExist(statErr) {
		return statErr
	}
	if err = afs.MkdirAll(filepath.Dir(dest), dirMode); err != nil {
		return err
	}

	// This namespace is already excluded from public resource APIs. O_EXCL and
	// a random nonce ensure cleanup can only remove the part we created.
	var part string
	var dst afero.File
	for {
		var nonce [16]byte
		if _, err = rand.Read(nonce[:]); err != nil {
			return err
		}
		part = filepath.Join(filepath.Dir(dest), files.UploadPartPrefix+hex.EncodeToString(nonce[:])+".part")
		dst, err = afs.OpenFile(part, os.O_RDWR|os.O_CREATE|os.O_EXCL, fileMode)
		if !os.IsExist(err) {
			break
		}
	}
	if err != nil {
		return err
	}
	dstOpen := true
	partOwned := true
	var partInfo os.FileInfo
	defer func() {
		if dstOpen {
			if closeErr := dst.Close(); err == nil {
				err = closeErr
			}
		}
		if partOwned {
			if cleanupErr := removeCopyPart(afs, part, partInfo); cleanupErr != nil {
				err = errors.Join(err, cleanupErr)
			}
		}
	}()
	// Capture identity from the created handle, before any close/publication.
	// On Windows this eagerly records the file ID rather than resolving the old
	// pathname after Rename. Unknown identities must never authorize deletion.
	partInfo, err = dst.Stat()
	if err != nil {
		return err
	}
	copied, err := io.Copy(dst, src)
	if err != nil {
		return err
	}
	// EOF from a source truncated while copying is not a completed copy.
	after, err := src.Stat()
	if err != nil {
		return err
	}
	if copied != info.Size() || after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return io.ErrUnexpectedEOF
	}
	if err = afs.Chmod(part, info.Mode()); err != nil {
		return err
	}
	if err = dst.Sync(); err != nil {
		return err
	}
	err = dst.Close()
	dstOpen = false
	if err != nil {
		return err
	}
	err = src.Close()
	srcOpen = false
	if err != nil {
		return err
	}
	copyPublicationMu.Lock()
	defer copyPublicationMu.Unlock()
	// Never remove dest on failure: a native directory-sync failure can occur
	// after publication, and a concurrent writer's destination is never ours.
	err = files.PublishUpload(afs, part, dest, overwrite)
	if err == nil {
		partOwned = false
	}
	return err
}

// A failed publisher may have already renamed part, so its former name is no
// longer an ownership proof. Do not remove a newly created or unknown entry.
func removeCopyPart(afs afero.Fs, part string, created os.FileInfo) error {
	var current os.FileInfo
	var err error
	var noFollow bool
	if filesystem, ok := afs.(afero.Lstater); ok {
		current, noFollow, err = filesystem.LstatIfPossible(part)
	} else {
		current, err = afs.Stat(part)
	}
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("无法核对复制临时文件身份，已保留 %q: %w", part, err)
	}
	// Stat-only adapters can follow a foreign symlink to the original inode.
	// The memory adapter's object pointer is independently a strong identity.
	_, memoryObject := current.(*mem.FileInfo)
	if (!noFollow && !memoryObject) || !sameCopyPart(created, current) {
		return fmt.Errorf("复制临时文件身份已改变或无法确认，已保留 %q", part)
	}
	return afs.Remove(part)
}

func sameCopyPart(created, current os.FileInfo) bool {
	if created == nil || current == nil || !created.Mode().IsRegular() || !current.Mode().IsRegular() {
		return false
	}
	if os.SameFile(created, current) {
		return true
	}
	// afero's memory adapter has no native inode. Its public FileData pointer is
	// the underlying object's identity; size/name/mtime equality is not identity.
	before, beforeOK := created.(*mem.FileInfo)
	after, afterOK := current.(*mem.FileInfo)
	return beforeOK && afterOK && before.FileData != nil && before.FileData == after.FileData
}
