package fbhttp

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path"
	"strconv"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
)

const maxTextEditBytes = 10 << 20

var textEditMu sync.Mutex
var errTextEditConflict = errors.New("source changed during text edit")

// Conditional edits stage bytes on the same filesystem and publish only after
// the original version is checked again. A failed/cancelled read leaves it intact.
func conditionalResourceEdit(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	modified, err := time.Parse(time.RFC3339Nano, r.URL.Query().Get("expectedModified"))
	size, sizeErr := strconv.ParseInt(r.URL.Query().Get("expectedSize"), 10, 64)
	if err != nil || sizeErr != nil || size < 0 {
		return http.StatusBadRequest, errors.New("missing source version")
	}
	if r.ContentLength > maxTextEditBytes {
		return http.StatusRequestEntityTooLarge, errors.New("text exceeds edit limit")
	}
	textEditMu.Lock()
	defer textEditMu.Unlock()
	original, err := d.user.Fs.Stat(r.URL.Path)
	if err != nil {
		return errToStatus(err), err
	}
	if !original.Mode().IsRegular() || original.Size() != size || !original.ModTime().Equal(modified) {
		return http.StatusConflict, errors.New("source changed; reload before saving")
	}
	if lstater, ok := d.user.Fs.(interface {
		LstatIfPossible(string) (os.FileInfo, bool, error)
	}); ok {
		link, _, linkErr := lstater.LstatIfPossible(r.URL.Path)
		if linkErr != nil {
			return errToStatus(linkErr), linkErr
		}
		if link.Mode()&os.ModeSymlink != 0 {
			return http.StatusConflict, errors.New("edit the original file instead of replacing a symbolic link")
		}
	}
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return http.StatusInternalServerError, err
	}
	part := path.Join(path.Dir(r.URL.Path), files.UploadPartPrefix+hex.EncodeToString(nonce)+".part")
	staged, err := d.user.Fs.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, original.Mode().Perm())
	if err != nil {
		return errToStatus(err), err
	}
	defer func() { _ = staged.Close(); _ = d.user.Fs.Remove(part) }()
	count, err := io.Copy(staged, io.LimitReader(r.Body, maxTextEditBytes+1))
	if err != nil {
		return errToStatus(err), err
	}
	if count > maxTextEditBytes {
		return http.StatusRequestEntityTooLarge, errors.New("text exceeds edit limit")
	}
	if err = r.Context().Err(); err != nil {
		return errToStatus(err), err
	}
	if err = staged.Sync(); err != nil {
		return http.StatusInternalServerError, err
	}
	if err = staged.Close(); err != nil {
		return http.StatusInternalServerError, err
	}
	err = d.RunHook(func() error {
		current, statErr := d.user.Fs.Stat(r.URL.Path)
		if statErr != nil || current.Size() != size || !current.ModTime().Equal(modified) ||
			(original.Sys() != nil && current.Sys() != nil && !os.SameFile(original, current)) {
			return errTextEditConflict
		}
		if cancelErr := r.Context().Err(); cancelErr != nil {
			return cancelErr
		}
		return files.PublishUpload(d.user.Fs, part, r.URL.Path, true)
	}, "save", r.URL.Path, "", d.user)
	if errors.Is(err, errTextEditConflict) {
		return http.StatusConflict, errors.New("source changed; draft was not saved")
	}
	if err != nil {
		return errToStatus(err), err
	}
	recordHistory(d, "file.save", r.URL.Path, "", history.StatusSuccess)
	return http.StatusOK, nil
}
