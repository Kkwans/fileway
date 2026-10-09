package fbhttp

import (
	"encoding/json"
	"errors"
	"fmt"
	"mime"
	"net/http"
	"path"
	"strings"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/gorilla/mux"
)

type archiveEntryRequest struct {
	ArchiveWirePath string `json:"archiveWirePath"`
	EntryWirePath   string `json:"entryWirePath"`
	SourceSize      *int64 `json:"sourceSize"`
	SourceModified  *int64 `json:"sourceModified"`
}
type archiveEntryStatus struct {
	ID             string `json:"id"`
	State          string `json:"state"`
	Stage          string `json:"stage"`
	Name           string `json:"name"`
	EntryWirePath  string `json:"entryWirePath"`
	SourceSize     int64  `json:"sourceSize"`
	SourceModified int64  `json:"sourceModified"`
	Size           int64  `json:"size"`
	ProcessedBytes int64  `json:"processedBytes"`
	Asset          string `json:"asset,omitempty"`
	Error          string `json:"error,omitempty"`
}

func archiveEntryNamespace(d *data) string { return d.server.Root + "\x00" + d.user.Scope }
func publicArchiveEntry(input archivefs.EntryInput, status archivefs.EntryStatus) archiveEntryStatus {
	value := archiveEntryStatus{ID: status.ID, State: status.State, Stage: status.Stage,
		Name: files.DisplayName(path.Base(status.EntryPath)), EntryWirePath: files.EncodeWirePath(status.EntryPath),
		SourceSize: input.SourceSize, SourceModified: input.SourceModified, Size: status.Size,
		ProcessedBytes: status.ProcessedBytes, Error: status.Error}
	if status.State == "ready" {
		value.Asset = "/api/archives/open/" + status.ID + "/content"
	}
	return value
}
func archiveEntryErrorStatus(err error) int {
	switch {
	case errors.Is(err, archivefs.ErrEntryForbidden):
		return http.StatusForbidden
	case errors.Is(err, archivefs.ErrEntryNotFound):
		return http.StatusNotFound
	case errors.Is(err, archivefs.ErrEntryNotReady):
		return http.StatusConflict
	case errors.Is(err, archivefs.ErrEntryBudget):
		return http.StatusTooManyRequests
	default:
		return archiveHTTPStatus(err)
	}
}

// POST /api/archives/open. It writes only the selected entry into the cache's
// private instance directory; Create permission and user-directory writes are unnecessary.
func archiveEntryPrepareHandler(cache *archivefs.EntryCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download {
			return http.StatusForbidden, fmt.Errorf("没有读取压缩包的权限")
		}
		var request archiveEntryRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("包内文件参数无效")
		}
		if request.ArchiveWirePath == "" || request.EntryWirePath == "" || request.SourceSize == nil || request.SourceModified == nil || *request.SourceSize < 0 {
			return http.StatusBadRequest, fmt.Errorf("缺少压缩包原始路径或版本")
		}
		source, err := archiveFilesystemPath("", request.ArchiveWirePath)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("压缩包原始路径无效")
		}
		entry, err := archivefs.DecodeEntryWirePath(request.EntryWirePath)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("包内条目原始路径不安全")
		}
		if !d.Check(source) {
			return http.StatusForbidden, fmt.Errorf("没有访问该压缩包的权限")
		}
		input := archivefs.EntryInput{UserID: d.user.ID, Namespace: archiveEntryNamespace(d), Filesystem: d.user.Fs,
			ArchivePath: source, EntryPath: entry, SourceSize: *request.SourceSize, SourceModified: *request.SourceModified}
		status, err := cache.Prepare(input)
		if err != nil {
			return archiveEntryErrorStatus(err), fmt.Errorf("包内文件无法准备: %w", err)
		}
		code := http.StatusAccepted
		if status.State == "ready" {
			code = http.StatusOK
		}
		return renderJSONStatus(w, publicArchiveEntry(input, status), code)
	})
}
func authorizeArchiveEntry(cache *archivefs.EntryCache, d *data, id string, verify bool) (archivefs.EntryInput, archivefs.EntryStatus, int, error) {
	if !d.user.Perm.Download {
		return archivefs.EntryInput{}, archivefs.EntryStatus{}, http.StatusForbidden, fmt.Errorf("没有读取压缩包的权限")
	}
	input, status, err := cache.Lookup(d.user.ID, archiveEntryNamespace(d), id)
	if err != nil {
		return input, status, archiveEntryErrorStatus(err), err
	}
	if !d.Check(input.ArchivePath) {
		return input, status, http.StatusForbidden, fmt.Errorf("没有访问该压缩包的权限")
	}
	if verify {
		if err := archivefs.VerifyEntrySource(d.user.Fs, input); err != nil {
			return input, status, archiveEntryErrorStatus(err), err
		}
	}
	return input, status, 0, nil
}

// GET /api/archives/open/{id}; DELETE cancels preparation without following a changed source.
func archiveEntryStatusHandler(cache *archivefs.EntryCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		input, status, code, err := authorizeArchiveEntry(cache, d, mux.Vars(r)["id"], true)
		if err != nil {
			return code, err
		}
		return renderJSON(w, r, publicArchiveEntry(input, status))
	})
}
func archiveEntryCancelHandler(cache *archivefs.EntryCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		input, _, code, err := authorizeArchiveEntry(cache, d, mux.Vars(r)["id"], false)
		if err != nil {
			return code, err
		}
		status, err := cache.Cancel(d.user.ID, archiveEntryNamespace(d), mux.Vars(r)["id"])
		if err != nil {
			return archiveEntryErrorStatus(err), err
		}
		return renderJSON(w, r, publicArchiveEntry(input, status))
	})
}

// GET/HEAD /api/archives/open/{id}/content. A real seekable file supplies Range,
// length and conditional-request semantics only after preparation is complete.
func archiveEntryContentHandler(cache *archivefs.EntryCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		input, _, code, err := authorizeArchiveEntry(cache, d, mux.Vars(r)["id"], true)
		if err != nil {
			return code, err
		}
		file, status, err := cache.Open(d.user.ID, archiveEntryNamespace(d), mux.Vars(r)["id"])
		if err != nil {
			return archiveEntryErrorStatus(err), err
		}
		defer func() { _ = file.Close() }()
		name := files.DisplayName(path.Base(status.EntryPath))
		contentType := mime.TypeByExtension(strings.ToLower(path.Ext(name)))
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "private, no-store")
		w.Header().Set("Content-Disposition", mime.FormatMediaType("inline", map[string]string{"filename": name}))
		w.Header().Set("ETag", `"`+status.ID+`"`)
		if strings.Contains(contentType, "html") || strings.Contains(contentType, "svg") {
			w.Header().Set("Content-Security-Policy", "sandbox; default-src 'none'")
		}
		http.ServeContent(w, r, name, time.UnixMilli(input.SourceModified), file)
		return 0, nil
	})
}
