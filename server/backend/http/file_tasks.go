package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"
	"syscall"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/fileutils"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/spf13/afero"
)

type fileTransferItem struct {
	From      string `json:"from"`
	To        string `json:"to"`
	Name      string `json:"name,omitempty"`
	Size      int64  `json:"size,omitempty"`
	Modified  string `json:"modified,omitempty"`
	IsDir     bool   `json:"isDir,omitempty"`
	Overwrite bool   `json:"overwrite,omitempty"`
	Rename    bool   `json:"rename,omitempty"`
}

type fileTransferRequest struct {
	Action string             `json:"action"`
	Items  []fileTransferItem `json:"items"`
}

type fileTransferTaskArgs struct {
	Items          []fileTransferItem       `json:"items"`
	Completed      map[string]bool          `json:"completed,omitempty"`
	CompletedPaths []fileTransferResultItem `json:"completedPaths,omitempty"`
}

type fileTransferResult struct {
	Completed []fileTransferResultItem `json:"completed,omitempty"`
	Failed    []fileTransferFailure    `json:"failed,omitempty"`
}

type fileTransferResultItem struct {
	From string `json:"from"`
	To   string `json:"to"`
}

type fileTransferFailure struct {
	From  string `json:"from"`
	To    string `json:"to"`
	Error string `json:"error"`
}

type fileTransferCheckpoint struct {
	Completed map[string]bool `json:"completed,omitempty"`
}

var fileTransferGate = make(chan struct{}, 1)

var fileTransferTaskHandler = func(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var request fileTransferRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("无效的文件任务请求: %w", err)
		}
		if request.Action != "copy" && request.Action != "move" {
			return http.StatusBadRequest, fmt.Errorf("不支持的文件操作 %q", request.Action)
		}
		if len(request.Items) == 0 || len(request.Items) > 1000 {
			return http.StatusBadRequest, fmt.Errorf("文件任务项目数必须在 1 到 1000 之间")
		}
		if request.Action == "copy" && !d.user.Perm.Create {
			return http.StatusForbidden, fberrors.ErrPermissionDenied
		}
		if request.Action == "move" && (!d.user.Perm.Rename || !d.user.Perm.Create) {
			return http.StatusForbidden, fberrors.ErrPermissionDenied
		}

		args := fileTransferTaskArgs{Items: make([]fileTransferItem, 0, len(request.Items)), Completed: make(map[string]bool)}
		for index, item := range request.Items {
			from, err := normalizeTransferPath(item.From)
			if err != nil {
				return http.StatusBadRequest, err
			}
			to, err := normalizeTransferPath(item.To)
			if err != nil {
				return http.StatusBadRequest, err
			}
			if from == "/" || to == "/" || from == to {
				return http.StatusBadRequest, fmt.Errorf("第 %d 项的源和目标路径无效", index+1)
			}
			if err := checkParent(from, to); err != nil {
				return http.StatusBadRequest, err
			}
			if !d.Check(from) || !d.Check(to) {
				return http.StatusForbidden, fmt.Errorf("没有权限访问第 %d 项的路径", index+1)
			}
			info, err := lstatResource(d.user.Fs, from)
			if err != nil {
				return errToStatus(err), err
			}
			if info.Mode()&os.ModeSymlink != 0 {
				return http.StatusBadRequest, fmt.Errorf("不支持复制符号链接: %s", from)
			}
			if _, err := d.user.Fs.Stat(to); err == nil {
				if item.Rename {
					to = addVersionSuffix(to, d.user.Fs)
				} else if !item.Overwrite {
					return http.StatusConflict, fmt.Errorf("文件冲突: %s", to)
				}
			}
			if to == from {
				return http.StatusBadRequest, fmt.Errorf("第 %d 项的源和目标路径相同", index+1)
			}
			item.From, item.To, item.IsDir, item.Size = from, to, info.IsDir(), info.Size()
			args.Items = append(args.Items, item)
		}

		encoded, err := json.Marshal(args)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		taskType := tasks.TypeFileCopy
		title := fmt.Sprintf("复制 %d 项", len(args.Items))
		if request.Action == "move" {
			taskType = tasks.TypeFileMove
			title = fmt.Sprintf("移动 %d 项", len(args.Items))
		}
		task, err := enqueueTask(runtime, d, d.user, taskType, title, encoded, "")
		if err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "file."+request.Action, title, task.ID, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

func normalizeTransferPath(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", fmt.Errorf("路径不能为空")
	}
	if value == "/files" || strings.HasPrefix(value, "/files/") {
		value = strings.TrimPrefix(value, "/files")
		parts := strings.Split(value, "/")
		for index, part := range parts {
			decoded, err := url.PathUnescape(part)
			if err != nil {
				return "", fmt.Errorf("路径编码无效: %w", err)
			}
			parts[index] = decoded
		}
		value = strings.Join(parts, "/")
	}
	return normalizeResourcePath(value), nil
}

func lstatResource(afs afero.Fs, name string) (os.FileInfo, error) {
	if lstater, ok := afs.(afero.Lstater); ok {
		info, _, err := lstater.LstatIfPossible(name)
		return info, err
	}
	return afs.Stat(name)
}

func fileTransferRunner(d *data, task *tasks.Task, args fileTransferTaskArgs) tasks.Runner {
	if args.Completed == nil {
		args.Completed = make(map[string]bool)
	}
	return func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		fileTransferGate <- struct{}{}
		defer func() { <-fileTransferGate }()

		result := fileTransferResult{}
		totalBytes := int64(0)
		for _, item := range args.Items {
			totalBytes += item.Size
		}
		processedItems := 0
		processedBytes := int64(0)
		checkpoint := fileTransferCheckpoint{Completed: args.Completed}
		checkpointJSON := func() json.RawMessage {
			encoded, _ := json.Marshal(checkpoint)
			return encoded
		}
		if err := report(tasks.Progress{TotalItems: len(args.Items), TotalBytes: totalBytes}); err != nil {
			return nil, err
		}

		for index, item := range args.Items {
			if err := ctx.Err(); err != nil {
				return marshalFileTransferResult(result), err
			}
			if completedPath(args.CompletedPaths, item) || completedCheckpoint(d.user.Fs, checkpoint.Completed, item) {
				if destinationMatchesItem(d.user.Fs, item) {
					processedItems++
					processedBytes += item.Size
					_ = report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()})
					result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
					continue
				}
				for key := range checkpoint.Completed {
					if strings.HasPrefix(key, item.From+"\x00"+item.To+"\x00") {
						delete(checkpoint.Completed, key)
					}
				}
			}
			key, info, err := fileTransferIdentity(d.user.Fs, item)
			if err != nil {
				result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
				continue
			}

			if task.Type == tasks.TypeFileMove {
				// A same-filesystem move is an atomic rename. Only an explicit
				// cross-device error is allowed to fall through to the resumable
				// copy path below; permission and conflict errors must be reported.
				if _, destinationErr := lstatResource(d.user.Fs, item.To); errors.Is(destinationErr, os.ErrNotExist) {
					renamed, renameErr := tryFastMove(d.user.Fs, item.From, item.To)
					if renameErr != nil {
						result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: renameErr.Error()})
						continue
					}
					if renamed {
						if metadataErr := rewritePathMetadata(d, item.From, item.To); metadataErr != nil {
							rollbackErr := d.user.Fs.Rename(item.To, item.From)
							if rollbackErr != nil {
								metadataErr = errors.Join(metadataErr, fmt.Errorf("元数据更新失败后的回滚失败: %w", rollbackErr))
							}
							result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: metadataErr.Error()})
							continue
						}
						processedItems++
						processedBytes += processedBytesForItem(info, item)
						checkpoint.Completed[key] = true
						result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
						if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
							return marshalFileTransferResult(result), err
						}
						continue
					}
				}
			}

			temp := path.Join(path.Dir(item.To), ".nfb-transfer-"+task.ID+fmt.Sprintf("-%d", index))
			_ = d.user.Fs.RemoveAll(temp)
			copyBytes := processedBytes
			err = fileutils.CopyContextProgress(ctx, d.user.Fs, item.From, temp, d.settings.FileMode, d.settings.DirMode, func(delta int64) error {
				copyBytes += delta
				return report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: copyBytes})
			})
			if err == nil {
				if _, statErr := d.user.Fs.Stat(item.To); statErr == nil {
					if !item.Overwrite {
						err = fmt.Errorf("文件冲突: %s", item.To)
					} else {
						err = d.user.Fs.RemoveAll(item.To)
					}
				}
			}
			if err == nil {
				err = d.user.Fs.Rename(temp, item.To)
			}
			if err == nil && task.Type == tasks.TypeFileMove {
				// Update path metadata before deleting the source. A metadata
				// failure therefore leaves both paths available for inspection
				// instead of losing the user's original file.
				err = rewritePathMetadata(d, item.From, item.To)
				if err == nil {
					err = d.user.Fs.RemoveAll(item.From)
				}
			}
			_ = d.user.Fs.RemoveAll(temp)
			if err != nil {
				result.Failed = append(result.Failed, fileTransferFailure{From: item.From, To: item.To, Error: err.Error()})
				if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
					return marshalFileTransferResult(result), ctx.Err()
				}
				continue
			}

			processedItems++
			processedBytes = copyBytes
			if processedBytes < processedBytesForItem(info, item) {
				processedBytes = processedBytesForItem(info, item)
			}
			checkpoint.Completed[key] = true
			result.Completed = append(result.Completed, fileTransferResultItem{From: item.From, To: item.To})
			if err := report(tasks.Progress{TotalItems: len(args.Items), ProcessedItems: processedItems, TotalBytes: totalBytes, ProcessedBytes: processedBytes, Checkpoint: checkpointJSON()}); err != nil {
				return marshalFileTransferResult(result), err
			}
		}

		encoded := marshalFileTransferResult(result)
		if len(result.Failed) > 0 {
			return encoded, fmt.Errorf("%d 项文件操作失败", len(result.Failed))
		}
		return encoded, nil
	}
}

func tryFastMove(afs afero.Fs, from, to string) (bool, error) {
	err := afs.Rename(from, to)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, syscall.EXDEV) {
		return false, nil
	}
	return false, err
}

func fileTransferIdentity(afs afero.Fs, item fileTransferItem) (string, os.FileInfo, error) {
	info, err := lstatResource(afs, item.From)
	if err != nil {
		return "", nil, err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return "", nil, fmt.Errorf("不支持复制符号链接: %s", item.From)
	}
	return fmt.Sprintf("%s\x00%s\x00%d\x00%d\x00%t", item.From, item.To, info.Size(), info.ModTime().UnixNano(), info.IsDir()), info, nil
}

func destinationMatchesItem(afs afero.Fs, item fileTransferItem) bool {
	info, err := lstatResource(afs, item.To)
	if err != nil {
		return false
	}
	return info.IsDir() == item.IsDir && info.Size() == item.Size
}

func completedPath(completed []fileTransferResultItem, item fileTransferItem) bool {
	for _, saved := range completed {
		if saved.From == item.From && saved.To == item.To {
			return true
		}
	}
	return false
}

func completedCheckpoint(afs afero.Fs, completed map[string]bool, item fileTransferItem) bool {
	if len(completed) == 0 {
		return false
	}
	if _, err := lstatResource(afs, item.From); err == nil {
		key, _, keyErr := fileTransferIdentity(afs, item)
		return keyErr == nil && completed[key]
	}
	prefix := item.From + "\x00" + item.To + "\x00"
	for key, saved := range completed {
		if saved && strings.HasPrefix(key, prefix) {
			return true
		}
	}
	return false
}

func processedBytesForItem(info os.FileInfo, item fileTransferItem) int64 {
	if info.IsDir() {
		return item.Size
	}
	return info.Size()
}

func marshalFileTransferResult(result fileTransferResult) json.RawMessage {
	encoded, _ := json.Marshal(result)
	return encoded
}

func resumeFileTransferArgs(original *tasks.Task) (json.RawMessage, error) {
	var args fileTransferTaskArgs
	if err := json.Unmarshal(original.Args, &args); err != nil {
		return nil, fmt.Errorf("任务参数损坏: %w", err)
	}
	if args.Completed == nil {
		args.Completed = make(map[string]bool)
	}
	var checkpoint fileTransferCheckpoint
	if len(original.Result) > 0 && json.Unmarshal(original.Result, &checkpoint) == nil && len(checkpoint.Completed) > 0 {
		for key, completed := range checkpoint.Completed {
			if completed {
				args.Completed[key] = true
			}
		}
	}
	var result fileTransferResult
	if len(original.Result) > 0 && json.Unmarshal(original.Result, &result) == nil {
		args.CompletedPaths = append(args.CompletedPaths, result.Completed...)
	}
	encoded, err := json.Marshal(args)
	if err != nil {
		return nil, fmt.Errorf("任务恢复参数生成失败: %w", err)
	}
	return encoded, nil
}
