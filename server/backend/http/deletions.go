package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

// pendingDeletionHandler is the single UI entry point for destructive work.
// It validates access before queuing and the task runner repeats the checks at
// execution time after the three-second undo window.
func pendingDeletionHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Delete {
			return http.StatusForbidden, fmt.Errorf("没有永久删除权限")
		}
		// HTTP input is a literal/wire request, not a persisted legacy task.
		type requestArgs pendingDeletionArgs
		var input requestArgs
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			return http.StatusBadRequest, fmt.Errorf("待删除参数无效: %w", err)
		}
		request := pendingDeletionArgs(input)
		switch request.Kind {
		case "resources":
			if (len(request.Paths) == 0 && len(request.WirePaths) == 0) || len(request.IDs) != 0 {
				return http.StatusBadRequest, fmt.Errorf("resources 需要 paths 且不能包含 ids")
			}
			if request.WirePaths != nil {
				if request.Paths != nil && len(request.Paths) != len(request.WirePaths) {
					return http.StatusBadRequest, fmt.Errorf("显示路径与原始路径数量不一致")
				}
				rawPaths := make([]string, len(request.WirePaths))
				for index, wire := range request.WirePaths {
					display := ""
					if request.Paths != nil {
						display = request.Paths[index]
						if display == "" {
							return http.StatusBadRequest, fmt.Errorf("显示路径不能为空")
						}
					}
					if wire == "" {
						return http.StatusBadRequest, fmt.Errorf("原始路径不能为空")
					}
					raw, err := resourceOperationPath(display, wire, false)
					if err != nil {
						return http.StatusBadRequest, err
					}
					rawPaths[index] = raw
				}
				request.Paths = rawPaths
			} else {
				for index, value := range request.Paths {
					raw, err := literalOperationPath(value)
					if err != nil {
						return http.StatusBadRequest, err
					}
					request.Paths[index] = raw
				}
			}
			seen := make(map[string]struct{}, len(request.Paths))
			for index, resourcePath := range request.Paths {
				if resourcePath == "/" {
					return http.StatusBadRequest, fmt.Errorf("不能永久删除文件系统根目录")
				}
				if _, exists := seen[resourcePath]; exists {
					return http.StatusBadRequest, fmt.Errorf("待删除路径重复: %s", resourcePath)
				}
				seen[resourcePath] = struct{}{}
				if !d.Check(resourcePath) {
					return http.StatusForbidden, fmt.Errorf("无权访问第 %d 个路径", index+1)
				}
				if _, err := files.NewFileInfo(&files.FileOptions{
					Fs: d.user.Fs, Path: resourcePath, Modify: d.user.Perm.Modify,
					Expand: false, ReadHeader: d.server.TypeDetectionByHeader, Checker: d,
				}); err != nil {
					return errToStatus(err), err
				}
			}
		case "trash-items":
			if len(request.IDs) == 0 || len(request.Paths) != 0 || len(request.WirePaths) != 0 {
				return http.StatusBadRequest, fmt.Errorf("trash-items 需要 ids 且不能包含 paths")
			}
			for _, id := range request.IDs {
				if id == "" {
					return http.StatusBadRequest, fmt.Errorf("回收站项目 id 不能为空")
				}
				if _, err := d.store.Trash.Get(d.user.ID, id, d.user.Perm.Admin); err != nil {
					return trashErrorStatus(err), err
				}
			}
		case "trash-all":
			if len(request.Paths) != 0 || len(request.WirePaths) != 0 || len(request.IDs) != 0 {
				return http.StatusBadRequest, fmt.Errorf("trash-all 不能包含 paths 或 ids")
			}
		default:
			return http.StatusBadRequest, fmt.Errorf("不支持的待删除类型 %q", request.Kind)
		}

		task, err := enqueuePendingDeletionTask(runtime, d, request)
		if err != nil {
			return taskErrorStatus(err), err
		}
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}
