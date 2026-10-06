package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

var archiveExtractionSlot = make(chan struct{}, 1)

type archiveExtractRequest struct {
	ArchivePath string   `json:"archivePath"`
	Destination string   `json:"destination"`
	Selected    []string `json:"selected"`
}

type archiveExtractTaskArgs struct {
	ArchivePath    string   `json:"archivePath"`
	Destination    string   `json:"destination"`
	Selected       []string `json:"selected"`
	SourceSize     int64    `json:"sourceSize"`
	SourceModified int64    `json:"sourceModified"`
}

var archiveEntriesHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Download {
		return http.StatusForbidden, fmt.Errorf("没有读取压缩包内容的权限")
	}
	archivePath := pathmeta.Clean(r.URL.Query().Get("path"))
	if r.URL.Query().Get("path") == "" {
		return http.StatusBadRequest, fmt.Errorf("压缩包路径不能为空")
	}
	if !d.Check(archivePath) {
		return http.StatusForbidden, fmt.Errorf("没有访问压缩包的权限")
	}
	listing, err := archivefs.List(r.Context(), d.user.Fs, archivePath, archivefs.DefaultLimits())
	if err != nil {
		return archiveHTTPStatus(err), err
	}
	return renderJSON(w, r, listing)
})

func archiveExtractStartHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download || !d.user.Perm.Create {
			return http.StatusForbidden, fmt.Errorf("没有读取压缩包或创建文件的权限")
		}
		var request archiveExtractRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("解压参数无效: %w", err)
		}
		if request.ArchivePath == "" || request.Destination == "" {
			return http.StatusBadRequest, fmt.Errorf("压缩包和目标目录不能为空")
		}
		archivePath := pathmeta.Clean(request.ArchivePath)
		destination := pathmeta.Clean(request.Destination)
		if !d.Check(archivePath) || !d.Check(destination) {
			return http.StatusForbidden, fmt.Errorf("没有访问压缩包或目标目录的权限")
		}
		selected, err := archivefs.NormalizeSelections(request.Selected, archivefs.DefaultMaxSelected)
		if err != nil {
			return http.StatusBadRequest, err
		}
		destinationInfo, err := d.user.Fs.Stat(destination)
		if err != nil {
			return errToStatus(err), fmt.Errorf("无法读取目标目录: %w", err)
		}
		if !destinationInfo.IsDir() {
			return http.StatusBadRequest, fmt.Errorf("解压目标不是目录")
		}
		listing, err := archivefs.List(r.Context(), d.user.Fs, archivePath, archivefs.DefaultLimits())
		if err != nil {
			return archiveHTTPStatus(err), err
		}
		if listing.Truncated {
			return http.StatusUnprocessableEntity, fmt.Errorf("压缩包超过安全限制: %s", listing.LimitReason)
		}
		args, err := json.Marshal(archiveExtractTaskArgs{
			ArchivePath: archivePath, Destination: destination, Selected: selected,
			SourceSize: listing.SourceSize, SourceModified: listing.SourceModified,
		})
		if err != nil {
			return http.StatusInternalServerError, err
		}
		title := "解压 " + path.Base(archivePath)
		task, err := enqueueTask(runtime, d, d.user, tasks.TypeArchiveExtract, title, args, "")
		if err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "archive.extract", archivePath, destination, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

var archiveExtractResultHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	task, err := d.store.Tasks.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return taskErrorStatus(err), err
	}
	if task.Type != tasks.TypeArchiveExtract {
		return http.StatusNotFound, tasks.ErrNotExist
	}
	if task.Status != tasks.StatusCompleted || len(task.Result) == 0 {
		return http.StatusConflict, fmt.Errorf("解压结果尚未完成")
	}
	var report archivefs.ExtractReport
	if err := json.Unmarshal(task.Result, &report); err != nil {
		return http.StatusInternalServerError, fmt.Errorf("解压结果损坏: %w", err)
	}
	return renderJSON(w, r, &report)
})

func archiveExtractRunner(d *data, task *tasks.Task, args archiveExtractTaskArgs) tasks.Runner {
	return func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		select {
		case archiveExtractionSlot <- struct{}{}:
			defer func() { <-archiveExtractionSlot }()
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		result, err := archivefs.Extract(ctx, d.user.Fs, archivefs.ExtractOptions{
			ArchivePath: args.ArchivePath, Destination: args.Destination,
			Selected: args.Selected, SourceSize: args.SourceSize, SourceModified: args.SourceModified,
			FileMode: d.settings.FileMode, DirMode: d.settings.DirMode,
			Limits: archivefs.DefaultLimits(), Checker: d,
		}, func(progress archivefs.ExtractProgress) error {
			return report(tasks.Progress{
				TotalItems: progress.TotalItems, ProcessedItems: progress.ProcessedItems,
				TotalBytes: progress.TotalBytes, ProcessedBytes: progress.ProcessedBytes,
			})
		})
		if err != nil {
			return nil, err
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			return nil, err
		}
		return encoded, nil
	}
}

func archiveHTTPStatus(err error) int {
	switch {
	case errors.Is(err, archivefs.ErrUnsupportedFormat):
		return http.StatusUnsupportedMediaType
	case errors.Is(err, archivefs.ErrLimitExceeded):
		return http.StatusUnprocessableEntity
	case errors.Is(err, archivefs.ErrUnsafeEntry):
		return http.StatusBadRequest
	case errors.Is(err, archivefs.ErrArchiveChanged):
		return http.StatusConflict
	case errors.Is(err, archivefs.ErrInvalidArchive):
		return http.StatusUnprocessableEntity
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, os.ErrNotExist):
		return http.StatusNotFound
	default:
		return http.StatusInternalServerError
	}
}
