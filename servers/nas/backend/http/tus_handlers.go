package fbhttp

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/transfers"
)

// keepUploadActive periodically touches the cache entry to prevent eviction during transfer
func keepUploadActive(cache UploadCache, filePath string) func() {
	stop := make(chan bool)

	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				cache.Touch(filePath)
			}
		}
	}()

	return func() {
		close(stop)
	}
}

func tusPostHandler(cache UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Create || !d.Check(r.URL.Path) {
			return http.StatusForbidden, fmt.Errorf("没有上传权限")
		}
		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})
		switch {
		case errors.Is(err, afero.ErrFileNotFound):
			dirPath := filepath.Dir(r.URL.Path)
			if _, statErr := d.user.Fs.Stat(dirPath); os.IsNotExist(statErr) {
				if mkdirErr := d.user.Fs.MkdirAll(dirPath, d.settings.DirMode); mkdirErr != nil {
					return http.StatusInternalServerError, err
				}
			}
		case err != nil:
			return errToStatus(err), err
		}

		fileFlags := os.O_CREATE | os.O_WRONLY

		// if file exists
		if file != nil {
			if file.IsDir {
				return http.StatusBadRequest, fmt.Errorf("不能上传到目录 %s", file.RealPath())
			}

			// Existing files will remain untouched unless explicitly instructed to override
			if r.URL.Query().Get("override") != "true" {
				return http.StatusConflict, fmt.Errorf("文件冲突")
			}

			// Permission for overwriting the file
			if !d.user.Perm.Modify {
				return http.StatusForbidden, fmt.Errorf("没有上传权限")
			}

			fileFlags |= os.O_TRUNC
		}

		openFile, err := d.user.Fs.OpenFile(r.URL.Path, fileFlags, d.settings.FileMode)
		if err != nil {
			return errToStatus(err), err
		}
		defer func() { _ = openFile.Close() }()

		file, err = files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: false,
			Checker:    d,
			Content:    false,
		})
		if err != nil {
			return errToStatus(err), err
		}

		uploadLength, err := getUploadLength(r)
		if err != nil || uploadLength < 0 {
			return http.StatusBadRequest, fmt.Errorf("无效的上传长度: %w", err)
		}

		// Enables the user to utilize the PATCH endpoint for uploading file data
		cache.Register(file.RealPath(), uploadLength)
		if transferID := transferIDFromRequest(r); transferID != "" && d.store.Transfers != nil {
			if item, ensureErr := d.store.Transfers.Ensure(d.user.ID, transferID, transfers.KindUpload, file.Name, r.URL.Path, uploadLength); ensureErr == nil && applyUploadBatchMetadata(item, r) {
				_ = d.store.Transfers.Update(item)
			}
		}

		basePath := "/" + strings.Trim(strings.TrimSpace(d.server.BaseURL), "/")
		if basePath == "/" {
			basePath = ""
		}

		location := basePath + "/api/tus" + r.URL.EscapedPath()
		if transferID := transferIDFromRequest(r); transferID != "" {
			location += "?transfer=" + url.QueryEscape(transferID)
		}
		w.Header().Set("Location", location)
		return http.StatusCreated, nil
	})
}

func tusHeadHandler(cache UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		w.Header().Set("Cache-Control", "no-store")
		if !d.user.Perm.Create || !d.Check(r.URL.Path) {
			return http.StatusForbidden, fmt.Errorf("没有上传权限")
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})
		if err != nil {
			return errToStatus(err), err
		}

		uploadLength, err := cache.GetLength(file.RealPath())
		if err != nil {
			return http.StatusNotFound, err
		}

		w.Header().Set("Upload-Offset", strconv.FormatInt(file.Size, 10))
		w.Header().Set("Upload-Length", strconv.FormatInt(uploadLength, 10))

		return http.StatusOK, nil
	})
}

func tusPatchHandler(cache UploadCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Create || !d.Check(r.URL.Path) {
			return http.StatusForbidden, fmt.Errorf("没有上传权限")
		}
		if r.Header.Get("Content-Type") != "application/offset+octet-stream" {
			return http.StatusUnsupportedMediaType, fmt.Errorf("不支持的文件类型")
		}

		uploadOffset, err := getUploadOffset(r)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("无效的上传偏移量")
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})

		switch {
		case errors.Is(err, afero.ErrFileNotFound):
			return http.StatusNotFound, fmt.Errorf("file not found")
		case err != nil:
			return errToStatus(err), err
		}

		uploadLength, err := cache.GetLength(file.RealPath())
		if err != nil {
			return http.StatusNotFound, err
		}

		// Prevent the upload from being evicted during the transfer
		stop := keepUploadActive(cache, file.RealPath())
		defer stop()

		switch {
		case file.IsDir:
			return http.StatusBadRequest, fmt.Errorf("不能上传到目录 %s", file.RealPath())
		case file.Size != uploadOffset:
			return http.StatusConflict, fmt.Errorf(
				"%s 文件大小与提供的偏移量不匹配: %d",
				file.RealPath(),
				uploadOffset,
			)
		}

		openFile, err := d.user.Fs.OpenFile(r.URL.Path, os.O_WRONLY|os.O_APPEND, d.settings.FileMode)
		if err != nil {
			return http.StatusInternalServerError, fmt.Errorf("无法打开文件: %w", err)
		}
		defer func() { _ = openFile.Close() }()

		_, err = openFile.Seek(uploadOffset, 0)
		if err != nil {
			return http.StatusInternalServerError, fmt.Errorf("无法定位文件: %w", err)
		}

		defer func() { _ = r.Body.Close() }()
		bytesWritten, err := io.Copy(openFile, r.Body)
		if err != nil {
			return http.StatusInternalServerError, fmt.Errorf("无法写入文件: %w", err)
		}

		// Sync the file to ensure all data is written to storage
		// to prevent file corruption.
		if err := openFile.Sync(); err != nil {
			return http.StatusInternalServerError, fmt.Errorf("无法同步文件: %w", err)
		}

		newOffset := uploadOffset + bytesWritten
		w.Header().Set("Upload-Offset", strconv.FormatInt(newOffset, 10))
		transferID := transferIDFromRequest(r)
		if transferID != "" && d.store.Transfers != nil {
			if latest, progressErr := d.store.Transfers.Progress(transferID, d.user.ID, newOffset); progressErr == nil {
				publishTransfer(latest)
			}
		}

		if newOffset >= uploadLength {
			cache.Complete(file.RealPath())
			if transferID != "" && d.store.Transfers != nil {
				if latest, statusErr := d.store.Transfers.SetStatus(transferID, d.user.ID, transfers.StatusCompleted, ""); statusErr == nil {
					publishTransfer(latest)
				}
			}
			_ = d.RunHook(func() error { return nil }, "upload", r.URL.Path, "", d.user)
			recordHistory(d, "file.upload", r.URL.Path, "", history.StatusSuccess)
		}

		return http.StatusNoContent, nil
	})
}

func tusDeleteHandler(cache UploadCache) handleFunc {
	return withUser(func(_ http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if r.URL.Path == "/" || !d.user.Perm.Delete {
			return http.StatusForbidden, fmt.Errorf("没有删除权限")
		}

		file, err := files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       r.URL.Path,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: d.server.TypeDetectionByHeader,
			Checker:    d,
		})
		if err != nil {
			return errToStatus(err), err
		}

		_, err = cache.GetLength(file.RealPath())
		if err != nil {
			return http.StatusNotFound, err
		}

		err = d.user.Fs.RemoveAll(r.URL.Path)
		if err != nil {
			return errToStatus(err), err
		}

		cache.Complete(file.RealPath())
		if transferID := transferIDFromRequest(r); transferID != "" && d.store.Transfers != nil {
			if latest, statusErr := d.store.Transfers.SetStatus(transferID, d.user.ID, transfers.StatusCanceled, ""); statusErr == nil {
				publishTransfer(latest)
			}
		}

		return http.StatusNoContent, nil
	})
}

func getUploadLength(r *http.Request) (int64, error) {
	uploadOffset, err := strconv.ParseInt(r.Header.Get("Upload-Length"), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("无效的上传长度: %w", err)
	}
	return uploadOffset, nil
}

func getUploadOffset(r *http.Request) (int64, error) {
	uploadOffset, err := strconv.ParseInt(r.Header.Get("Upload-Offset"), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("无效的上传偏移量: %w", err)
	}
	return uploadOffset, nil
}

func transferIDFromRequest(r *http.Request) string {
	if value := strings.TrimSpace(r.URL.Query().Get("transfer")); value != "" {
		return value
	}
	return strings.TrimSpace(r.Header.Get("X-Transfer-ID"))
}
