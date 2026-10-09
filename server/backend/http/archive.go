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
	"strings"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/archivefs"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

var archiveExtractionSlot = make(chan struct{}, 1)

type archiveExtractRequest struct {
	ArchivePath         string   `json:"archivePath"`
	Destination         string   `json:"destination"`
	Selected            []string `json:"selected"`
	ArchiveWirePath     string   `json:"archiveWirePath,omitempty"`
	DestinationWirePath string   `json:"destinationWirePath,omitempty"`
	SelectedWirePaths   []string `json:"selectedWirePaths,omitempty"`
}

func archiveFilesystemPath(display, wire string) (string, error) {
	if wire == "" {
		return archivefs.NormalizeFilesystemPath(display)
	}
	raw, err := decodeResourceWirePath(wire)
	if err != nil {
		return "", err
	}
	raw, err = archivefs.NormalizeFilesystemPath(raw)
	if err != nil {
		return "", err
	}
	if display != "" {
		canonical, err := archivefs.NormalizeFilesystemPath(display)
		if err != nil {
			return "", err
		}
		if canonical != files.DisplayPath(raw) {
			return "", fmt.Errorf("显示路径与原始路径不一致")
		}
	}
	return raw, nil
}

func archiveSelections(display, wires []string) ([]string, error) {
	if wires == nil {
		return archivefs.NormalizeSelections(display, archivefs.DefaultMaxSelected)
	}
	if display != nil && len(display) != len(wires) {
		return nil, fmt.Errorf("选择条目与原始路径数量不一致")
	}
	selected := make([]string, len(wires))
	for index, wire := range wires {
		raw, err := archivefs.DecodeEntryWirePath(wire)
		if err != nil {
			return nil, fmt.Errorf("所选条目原始路径无效: %w", err)
		}
		if display != nil {
			canonical, err := archivefs.NormalizeSelections([]string{display[index]}, 1)
			if err != nil || canonical[0] != files.DisplayPath(raw) {
				return nil, fmt.Errorf("条目显示路径与原始路径不一致")
			}
		}
		selected[index] = raw
	}
	return archivefs.NormalizeSelections(selected, archivefs.DefaultMaxSelected)
}

// Persist reversible wire identities beside compatible display fields. Task
// workers continue to consume raw strings after this private codec restores them.
func (args archiveExtractTaskArgs) MarshalJSON() ([]byte, error) {
	selected, wires := make([]string, len(args.Selected)), make([]string, len(args.Selected))
	for index, raw := range args.Selected {
		selected[index] = files.DisplayPath(raw)
		wires[index] = files.EncodeWirePath(raw)
	}
	return json.Marshal(struct {
		archiveExtractRequest
		SourceSize     int64 `json:"sourceSize"`
		SourceModified int64 `json:"sourceModified"`
	}{
		archiveExtractRequest: archiveExtractRequest{ArchivePath: files.DisplayPath(args.ArchivePath), ArchiveWirePath: files.EncodeWirePath(args.ArchivePath),
			Destination: files.DisplayPath(args.Destination), DestinationWirePath: files.EncodeWirePath(args.Destination), Selected: selected, SelectedWirePaths: wires},
		SourceSize: args.SourceSize, SourceModified: args.SourceModified,
	})
}

func (args *archiveExtractTaskArgs) UnmarshalJSON(data []byte) error {
	var row struct {
		archiveExtractRequest
		SourceSize     int64 `json:"sourceSize"`
		SourceModified int64 `json:"sourceModified"`
	}
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	if row.ArchiveWirePath == "" && strings.ContainsRune(row.ArchivePath, '\uFFFD') ||
		row.DestinationWirePath == "" && strings.ContainsRune(row.Destination, '\uFFFD') {
		return fmt.Errorf("历史任务原始路径无法确认，请重新提交解压")
	}
	if row.SelectedWirePaths == nil {
		for _, path := range row.Selected {
			if strings.ContainsRune(path, '\uFFFD') {
				return fmt.Errorf("历史任务条目原始路径无法确认，请重新提交解压")
			}
		}
	}
	archive, err := archiveFilesystemPath(row.ArchivePath, row.ArchiveWirePath)
	if err != nil {
		return err
	}
	destination, err := archiveFilesystemPath(row.Destination, row.DestinationWirePath)
	if err != nil {
		return err
	}
	selected, err := archiveSelections(row.Selected, row.SelectedWirePaths)
	if err != nil {
		return err
	}
	*args = archiveExtractTaskArgs{ArchivePath: archive, Destination: destination, Selected: selected, SourceSize: row.SourceSize, SourceModified: row.SourceModified}
	return nil
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
	archivePath, err := archiveFilesystemPath(r.URL.Query().Get("path"), r.URL.Query().Get("wirePath"))
	if err != nil {
		return http.StatusBadRequest, err
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
		archivePath, err := archiveFilesystemPath(request.ArchivePath, request.ArchiveWirePath)
		if err != nil {
			return http.StatusBadRequest, err
		}
		destination, err := archiveFilesystemPath(request.Destination, request.DestinationWirePath)
		if err != nil {
			return http.StatusBadRequest, err
		}
		if !d.Check(archivePath) || !d.Check(destination) {
			return http.StatusForbidden, fmt.Errorf("没有访问压缩包或目标目录的权限")
		}
		selected, err := archiveSelections(request.Selected, request.SelectedWirePaths)
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
