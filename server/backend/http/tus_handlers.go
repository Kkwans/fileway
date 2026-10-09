package fbhttp

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/uploads"
)

func tusPostHandler(_ UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if status, err := checkTus(w, r, d); err != nil {
			return status, err
		}
		if !d.user.Perm.Create {
			return 403, fmt.Errorf("没有上传权限")
		}
		length, err := getUploadLength(r)
		if err != nil || r.ContentLength > 0 {
			return 400, fmt.Errorf("上传长度或创建请求无效，文件未修改")
		}
		key, err := tusTargetKey(d, r.URL.Path)
		if err != nil {
			return 400, err
		}
		unlock := d.store.Uploads.Lock(key)
		defer unlock()
		id := transferIDFromRequest(r)
		if id == "" {
			id, err = newTusID()
			if err != nil {
				return 500, err
			}
		}
		if len(id) > 220 || strings.ContainsAny(id, "\r\n\x00") {
			return 400, fmt.Errorf("上传标识无效")
		}
		unlockID := d.store.Uploads.Lock("id:" + id)
		defer unlockID()
		if old, err := d.store.Uploads.Get(id, d.user.ID, tusPath(r), key); err == nil {
			if old.Length != length {
				return 409, fmt.Errorf("原上传的目标或长度已变化")
			}
			if old.State == uploads.Active || old.State == uploads.Completed {
				if err := tusState(d, old); err != nil {
					return tusErrorStatus(err), err
				}
				setTusLocation(w, r, d, old)
				tusHeaders(w, old)
				return 201, nil
			}
			return 409, fmt.Errorf("原上传已经结束，请创建新上传标识")
		} else if !errors.Is(err, uploads.ErrNotExist) {
			return tusErrorStatus(err), err
		}
		busy, err := d.store.Uploads.TargetBusy(key, time.Now())
		if err != nil {
			return 500, err
		}
		if busy {
			return 409, fmt.Errorf("目标已有未完成上传，请继续原任务或选择其他名称")
		}
		stamp := time.Now()
		row := &uploads.Session{ID: id, UserID: d.user.ID, WirePath: tusPath(r), RootKey: key, Length: length, State: uploads.Active, CreatedAt: stamp.UnixMilli()}
		final, statErr := d.user.Fs.Stat(r.URL.Path)
		if statErr == nil {
			if !final.Mode().IsRegular() {
				return 409, fmt.Errorf("目标不是普通文件")
			}
			if r.URL.Query().Get("override") != "true" {
				return 409, fmt.Errorf("目标文件已存在，未覆盖")
			}
			if !d.user.Perm.Modify {
				return 403, fmt.Errorf("没有覆盖权限")
			}
			row.Overwrite = true
			row.TargetSize = final.Size()
			row.TargetModified = final.ModTime().UnixNano()
			row.TargetIdentity = files.FileIdentity(d.user.Fs, r.URL.Path)
			if row.TargetIdentity != nil && !row.TargetIdentity.IsRegular() {
				return 409, fmt.Errorf("不能覆盖链接或非普通文件")
			}
		} else if !os.IsNotExist(statErr) {
			return errToStatus(statErr), statErr
		}
		partID, err := newTusID()
		if err != nil {
			return 500, err
		}
		part := path.Join(path.Dir(r.URL.Path), files.UploadPartPrefix+partID+".part")
		row.PartWire = (&url.URL{Path: part}).EscapedPath()
		uploads.Touch(row, stamp)
		if d.store.Transfers != nil {
			item, err := d.store.Transfers.Ensure(d.user.ID, id, transfers.KindUpload, path.Base(r.URL.Path), r.URL.Path, length)
			if err != nil || item.Target != r.URL.Path || item.BytesTotal != length {
				return 409, fmt.Errorf("上传记录与原目标或长度不匹配")
			}
			if applyUploadBatchMetadata(item, r) {
				if err := d.store.Transfers.Update(item); err != nil {
					return 500, err
				}
			}
		}
		if err := d.store.Uploads.Create(row); err != nil {
			return tusErrorStatus(err), err
		}
		if err := d.user.Fs.MkdirAll(path.Dir(part), d.settings.DirMode); err != nil {
			row.State = uploads.Changed
			_ = d.store.Uploads.Save(row)
			return errToStatus(err), err
		}
		file, err := d.user.Fs.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, d.settings.FileMode)
		if err != nil {
			row.State = uploads.Changed
			_ = d.store.Uploads.Save(row)
			return errToStatus(err), err
		}
		info, err := file.Stat()
		if err == nil {
			err = file.Sync()
		}
		closeErr := file.Close()
		if err == nil {
			err = closeErr
		}
		if err != nil {
			_ = d.user.Fs.Remove(part)
			row.State = uploads.Changed
			_ = d.store.Uploads.Save(row)
			return 500, err
		}
		row.Modified = info.ModTime().UnixNano()
		row.Identity = files.FileIdentity(d.user.Fs, part)
		if err := d.store.Uploads.Save(row); err != nil {
			return 500, err
		}
		if length == 0 {
			if err := tusPublish(d, row); err != nil {
				return tusErrorStatus(err), err
			}
			tusFinish(d, row, r.URL.Path)
		}
		setTusLocation(w, r, d, row)
		tusHeaders(w, row)
		return 201, nil
	})
}
func setTusLocation(w http.ResponseWriter, r *http.Request, d *data, row *uploads.Session) {
	base := "/" + strings.Trim(strings.TrimSpace(d.server.BaseURL), "/")
	if base == "/" {
		base = ""
	}
	w.Header().Set("Location", base+"/api/tus"+r.URL.EscapedPath()+"?transfer="+url.QueryEscape(row.ID))
}
func tusHeadHandler(_ UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if status, err := checkTus(w, r, d); err != nil {
			return status, err
		}
		if !d.user.Perm.Create {
			return 403, fmt.Errorf("没有上传权限")
		}
		key, err := tusTargetKey(d, r.URL.Path)
		if err != nil {
			return 400, err
		}
		unlock := d.store.Uploads.Lock(key)
		defer unlock()
		row, status, err := tusLookup(r, d, key)
		if err != nil {
			return status, err
		}
		if err := tusState(d, row); err != nil {
			return tusErrorStatus(err), err
		}
		if row.State == uploads.Active && row.Offset == row.Length {
			if err := tusPublish(d, row); err != nil {
				return tusErrorStatus(err), err
			}
			tusFinish(d, row, r.URL.Path)
		}
		if row.State == uploads.Active {
			uploads.Touch(row, time.Now())
			if err := d.store.Uploads.Save(row); err != nil {
				return 500, err
			}
		}
		tusHeaders(w, row)
		return 200, nil
	})
}
func tusPatchHandler(_ UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if status, err := checkTus(w, r, d); err != nil {
			return status, err
		}
		if !d.user.Perm.Create {
			return 403, fmt.Errorf("没有上传权限")
		}
		if r.Header.Get("Content-Type") != "application/offset+octet-stream" {
			return 415, fmt.Errorf("上传片段类型无效")
		}
		offset, err := getUploadOffset(r)
		if err != nil {
			return 400, err
		}
		key, err := tusTargetKey(d, r.URL.Path)
		if err != nil {
			return 400, err
		}
		unlock := d.store.Uploads.Lock(key)
		defer unlock()
		row, status, err := tusLookup(r, d, key)
		if err != nil {
			return status, err
		}
		if err := tusState(d, row); err != nil {
			return tusErrorStatus(err), err
		}
		if offset != row.Offset {
			return 409, fmt.Errorf("服务器实际位置已变化，请查询后续传")
		}
		if row.State == uploads.Completed {
			if r.ContentLength != 0 {
				return 409, fmt.Errorf("上传已经完成，未追加数据")
			}
			tusHeaders(w, row)
			return 204, nil
		}
		remaining := row.Length - row.Offset
		if r.ContentLength > remaining {
			return 413, fmt.Errorf("片段超过文件剩余长度，未写入")
		}
		part, err := tusPart(row)
		if err != nil {
			return 409, err
		}
		file, err := d.user.Fs.OpenFile(part, os.O_WRONLY, d.settings.FileMode)
		if err != nil {
			return errToStatus(err), err
		}
		// Error paths have already preserved the confirmed offset; final
		// publication explicitly checks Sync and Close below.
		defer func() { _ = file.Close() }()
		if _, err := file.Seek(row.Offset, io.SeekStart); err != nil {
			return 500, err
		}
		row.Writing = true
		row.ReservedTo = row.Length
		if r.ContentLength >= 0 {
			row.ReservedTo = row.Offset + r.ContentLength
		}
		uploads.Touch(row, time.Now())
		if err := d.store.Uploads.Save(row); err != nil {
			return 500, err
		}
		written, writeErr := io.Copy(file, http.MaxBytesReader(w, r.Body, remaining))
		syncErr := file.Sync()
		info, statErr := file.Stat()
		if statErr != nil {
			return 500, statErr
		}
		row.Offset = info.Size()
		row.Modified = info.ModTime().UnixNano()
		row.Writing = false
		uploads.Touch(row, time.Now())
		if err := d.store.Uploads.Save(row); err != nil {
			return 500, err
		}
		if d.store.Transfers != nil {
			if item, err := d.store.Transfers.Progress(row.ID, row.UserID, row.Offset); err == nil {
				publishTransfer(item)
			}
		}
		if writeErr != nil {
			var over *http.MaxBytesError
			if errors.As(writeErr, &over) {
				return 413, fmt.Errorf("片段超过剩余长度，实际位置已保存")
			}
			return 500, fmt.Errorf("上传中断，已保存 %d 字节，可查询后继续", written)
		}
		if syncErr != nil {
			return 500, syncErr
		}
		if row.Offset == row.Length {
			if err := file.Close(); err != nil {
				return 500, err
			}
			if err := tusPublish(d, row); err != nil {
				return tusErrorStatus(err), err
			}
			tusFinish(d, row, r.URL.Path)
		}
		tusHeaders(w, row)
		return 204, nil
	})
}
func tusDeleteHandler(_ UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if status, err := checkTus(w, r, d); err != nil {
			return status, err
		}
		if !d.user.Perm.Delete {
			return 403, fmt.Errorf("没有取消上传权限")
		}
		key, err := tusTargetKey(d, r.URL.Path)
		if err != nil {
			return 400, err
		}
		unlock := d.store.Uploads.Lock(key)
		defer unlock()
		row, status, err := tusLookup(r, d, key)
		if err != nil {
			return status, err
		}
		if row.State == uploads.Completed {
			return 409, fmt.Errorf("完整文件不会被取消上传删除")
		}
		if err := tusState(d, row); err != nil {
			return tusErrorStatus(err), err
		}
		part, err := tusPart(row)
		if err != nil {
			return 409, err
		}
		if _, err := tusIdentity(d, part, row); err != nil {
			return tusErrorStatus(err), err
		}
		if err := d.user.Fs.Remove(part); err != nil {
			return errToStatus(err), err
		}
		row.State = uploads.Canceled
		row.Writing = false
		if err := d.store.Uploads.Save(row); err != nil {
			return 500, err
		}
		if d.store.Transfers != nil {
			if item, err := d.store.Transfers.SetStatus(row.ID, row.UserID, transfers.StatusCanceled, ""); err == nil {
				publishTransfer(item)
			}
		}
		return 204, nil
	})
}
func tusFinish(d *data, row *uploads.Session, target string) {
	if d.store.Transfers != nil {
		if item, err := d.store.Transfers.Progress(row.ID, row.UserID, row.Offset); err == nil {
			publishTransfer(item)
		}
		if item, err := d.store.Transfers.SetStatus(row.ID, row.UserID, transfers.StatusCompleted, ""); err == nil {
			publishTransfer(item)
		}
	}
	_ = d.RunHook(func() error { return nil }, "upload", target, "", d.user)
	recordHistory(d, "file.upload", target, "", history.StatusSuccess)
}
func tusErrorStatus(err error) int {
	if errors.Is(err, uploads.ErrConflict) || errors.Is(err, os.ErrExist) {
		return 409
	}
	if os.IsNotExist(err) || errors.Is(err, uploads.ErrNotExist) {
		return 404
	}
	if errors.Is(err, uploads.ErrExpired) {
		return 410
	}
	return 500
}
func getUploadLength(r *http.Request) (int64, error) { return tusNumber(r.Header.Get("Upload-Length")) }
func getUploadOffset(r *http.Request) (int64, error) { return tusNumber(r.Header.Get("Upload-Offset")) }
func tusNumber(value string) (int64, error) {
	if value == "" || strings.IndexFunc(value, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
		return 0, fmt.Errorf("上传长度或位置无效")
	}
	return strconv.ParseInt(value, 10, 64)
}
func transferIDFromRequest(r *http.Request) string {
	if value := strings.TrimSpace(r.URL.Query().Get("transfer")); value != "" {
		return value
	}
	return strings.TrimSpace(r.Header.Get("X-Transfer-ID"))
}
