package fbhttp

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/storage"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
)

func tusPath(r *http.Request) string { return (&url.URL{Path: r.URL.Path}).EscapedPath() }
func tusTargetKey(d *data, value string) (string, error) {
	native := d.user.FullPath(value)
	if native == "" {
		return "", fmt.Errorf("上传目标无效")
	}
	native = filepath.Clean(native)
	// Resolve the closest existing ancestor. Newly created folders below a
	// symlink/junction must not change the session key after POST.
	ancestor := filepath.Dir(native)
	for {
		resolved, err := filepath.EvalSymlinks(ancestor)
		if err == nil {
			relative, err := filepath.Rel(ancestor, native)
			if err != nil {
				return "", err
			}
			native = filepath.Join(resolved, relative)
			break
		}
		if !os.IsNotExist(err) {
			return "", err
		}
		parent := filepath.Dir(ancestor)
		if parent == ancestor {
			break
		}
		ancestor = parent
	}
	if runtime.GOOS == "windows" {
		native = strings.ToLower(native)
	}
	return base64.RawURLEncoding.EncodeToString([]byte(native)), nil
}
func tusPart(row *uploads.Session) (string, error) {
	part, err := url.PathUnescape(row.PartWire)
	if err != nil || !files.IsUploadPartPath(part) || path.Dir(part) != path.Dir(mustUploadPath(row.WirePath)) {
		return "", uploads.ErrConflict
	}
	return part, nil
}
func mustUploadPath(wire string) string { value, _ := url.PathUnescape(wire); return value }
func tusHeaders(w http.ResponseWriter, row *uploads.Session) {
	w.Header().Set("Tus-Resumable", "1.0.0")
	w.Header().Set("Cache-Control", "no-store")
	if row != nil {
		w.Header().Set("Upload-Length", strconv.FormatInt(row.Length, 10))
		w.Header().Set("Upload-Offset", strconv.FormatInt(row.Offset, 10))
		if row.State == uploads.Active {
			w.Header().Set("Upload-Expires", time.UnixMilli(row.ExpiresAt).UTC().Format(http.TimeFormat))
		}
	}
}
func checkTus(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	tusHeaders(w, nil)
	if version := r.Header.Get("Tus-Resumable"); version != "" && version != "1.0.0" {
		w.Header().Set("Tus-Version", "1.0.0")
		return 412, fmt.Errorf("上传协议版本不支持")
	}
	query, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil || len(query["transfer"]) > 1 || len(r.Header.Values("X-Transfer-ID")) > 1 {
		return 400, fmt.Errorf("上传标识参数无效")
	}
	if transfer, header := query.Get("transfer"), r.Header.Get("X-Transfer-ID"); transfer != "" && header != "" && transfer != header {
		return 409, fmt.Errorf("上传会话标识不一致")
	}
	if d.store.Uploads == nil {
		return 503, fmt.Errorf("上传会话存储不可用，文件未修改")
	}
	if r.URL.Path == "/" || strings.HasSuffix(r.URL.Path, "/") || !d.Check(r.URL.Path) {
		return 403, fmt.Errorf("上传目标不允许访问")
	}
	return 0, nil
}
func newTusID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}
func tusLookup(r *http.Request, d *data, key string) (*uploads.Session, int, error) {
	id := transferIDFromRequest(r)
	var row *uploads.Session
	var err error
	if id == "" {
		row, err = d.store.Uploads.Find(d.user.ID, tusPath(r), key)
	} else {
		row, err = d.store.Uploads.Get(id, d.user.ID, tusPath(r), key)
	}
	if err != nil {
		if errors.Is(err, uploads.ErrNotExist) {
			return nil, 404, err
		}
		if errors.Is(err, uploads.ErrConflict) {
			return nil, 409, err
		}
		return nil, 500, err
	}
	if row.State == uploads.Expired || row.State == uploads.Canceled {
		return nil, 410, fmt.Errorf("原上传已经过期或取消")
	}
	if row.State == uploads.Changed {
		return nil, 409, fmt.Errorf("上传暂存文件身份已变化，文件未修改")
	}
	return row, 0, nil
}
func tusIdentity(d *data, name string, row *uploads.Session) (os.FileInfo, error) {
	info, err := d.user.Fs.Stat(name)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, uploads.ErrConflict
	}
	identity := files.FileIdentity(d.user.Fs, name)
	if row.Identity != nil && !row.Identity.Same(identity) {
		return nil, uploads.ErrConflict
	}
	return info, nil
}
func tusReconcile(d *data, row *uploads.Session) error {
	part, err := tusPart(row)
	if err != nil {
		return err
	}
	info, err := tusIdentity(d, part, row)
	if os.IsNotExist(err) && row.Offset == row.Length && row.Identity != nil {
		// Publication succeeded before a process died saving the terminal state.
		info, err = tusIdentity(d, mustUploadPath(row.WirePath), row)
		if err == nil && info.Size() == row.Length {
			row.State = uploads.Completed
			row.Writing = false
			return d.store.Uploads.Save(row)
		}
	}
	if err != nil {
		return err
	}
	if info.Size() != row.Offset || info.ModTime().UnixNano() != row.Modified {
		if !row.Writing || info.Size() < row.Offset || info.Size() > row.ReservedTo || info.Size() > row.Length {
			return uploads.ErrConflict
		}
		// A durable write intent owns only this private nonce part and bounded
		// append. Interrupted bytes are reconciled from its actual file size.
		row.Offset = info.Size()
		row.Modified = info.ModTime().UnixNano()
		row.Writing = false
		return d.store.Uploads.Save(row)
	}
	return nil
}
func tusState(d *data, row *uploads.Session) error {
	if row.State == uploads.Completed {
		info, err := tusIdentity(d, mustUploadPath(row.WirePath), row)
		if err != nil {
			return err
		}
		if info.Size() != row.Length || info.ModTime().UnixNano() != row.Modified {
			return uploads.ErrConflict
		}
		return nil
	}
	if row.ExpiresAt <= time.Now().UnixMilli() {
		return tusExpire(d, row)
	}
	return tusReconcile(d, row)
}
func tusExpire(d *data, row *uploads.Session) error {
	part, err := tusPart(row)
	if err != nil {
		return err
	}
	info, checkErr := tusIdentity(d, part, row)
	// Only the original nonce part with its last confirmed checkpoint is
	// removed. A replaced/externally changed file is retained for inspection.
	if checkErr == nil && row.Identity != nil && info.Size() == row.Offset && info.ModTime().UnixNano() == row.Modified {
		if err := d.user.Fs.Remove(part); err != nil {
			return err
		}
	}
	row.State = uploads.Expired
	row.Writing = false
	if err := d.store.Uploads.Save(row); err != nil {
		return err
	}
	if d.store.Transfers != nil {
		if item, err := d.store.Transfers.Get(d.user.ID, row.ID, false); err == nil {
			_, _ = d.store.Transfers.SetStatus(item.ID, item.UserID, transfers.StatusInterrupted, "未完成上传已超过7天保留期")
		}
	}
	return uploads.ErrExpired
}
func tusPublish(d *data, row *uploads.Session) error {
	if row.Offset != row.Length {
		return uploads.ErrConflict
	}
	part, err := tusPart(row)
	if err != nil {
		return err
	}
	info, err := tusIdentity(d, part, row)
	if err != nil || info.Size() != row.Length {
		if err != nil {
			return err
		}
		return uploads.ErrConflict
	}
	file, err := d.user.Fs.OpenFile(part, os.O_RDWR, d.settings.FileMode)
	if err != nil {
		return err
	}
	syncErr := file.Sync()
	closeErr := file.Close()
	if syncErr != nil {
		return syncErr
	}
	if closeErr != nil {
		return closeErr
	}
	target := mustUploadPath(row.WirePath)
	if row.Overwrite {
		current, err := d.user.Fs.Stat(target)
		if err != nil {
			return err
		}
		if current.Size() != row.TargetSize || current.ModTime().UnixNano() != row.TargetModified ||
			row.TargetIdentity != nil && !row.TargetIdentity.Same(files.FileIdentity(d.user.Fs, target)) {
			return uploads.ErrConflict
		}
	}
	if err := files.PublishUpload(d.user.Fs, part, target, row.Overwrite); err != nil {
		return err
	}
	row.State = uploads.Completed
	row.Writing = false
	row.Modified = info.ModTime().UnixNano()
	row.Identity = files.FileIdentity(d.user.Fs, target)
	return d.store.Uploads.Save(row)
}

// The existing server owns lifecycle and shutdown; no independent daemon or
// metadata store is introduced. Cleanup never addresses final target files.
func StartUploadCleanup(ctx context.Context, store *storage.Storage, server *settings.Server) {
	if store.Uploads == nil {
		return
	}
	go func() {
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				rows, err := store.Uploads.Due(time.Now())
				if err != nil {
					log.Printf("upload expiration lookup failed: %v", err)
					continue
				}
				for _, row := range rows {
					if ctx.Err() != nil {
						return
					}
					user, err := store.Users.Get(server.Root, row.UserID)
					if err != nil {
						continue
					}
					d := &data{store: store, server: server, user: user}
					target := mustUploadPath(row.WirePath)
					key, err := tusTargetKey(d, target)
					if err != nil || key != row.RootKey {
						continue
					}
					unlock := store.Uploads.Lock(key)
					latest, err := store.Uploads.Get(row.ID, row.UserID, row.WirePath, key)
					if err == nil && latest.State == uploads.Active && latest.ExpiresAt <= time.Now().UnixMilli() {
						_ = tusExpire(d, latest)
					}
					unlock()
				}
			}
		}
	}()
}
