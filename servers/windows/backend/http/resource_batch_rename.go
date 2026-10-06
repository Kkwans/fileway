package fbhttp

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path"
	"strings"
	"sync"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const maxBatchRenameItems = 500

var batchRenameMu sync.Mutex

type batchRenameRequest struct {
	Items  []batchRenameRequestItem `json:"items"`
	DryRun bool                     `json:"dryRun"`
}

type batchRenameRequestItem struct {
	From string `json:"from"`
	To   string `json:"to"`
}

type batchRenameResponse struct {
	Valid    bool                      `json:"valid"`
	Executed bool                      `json:"executed"`
	Items    []batchRenameResponseItem `json:"items"`
	Error    string                    `json:"error,omitempty"`
}

type batchRenameResponseItem struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

type plannedBatchRename struct {
	From string
	To   string
	Temp string
}

func resourceBatchRenameHandler(fileCache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Rename {
			return http.StatusForbidden, fmt.Errorf("没有重命名权限")
		}
		var request batchRenameRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("批量重命名参数无效: %w", err)
		}
		if len(request.Items) == 0 || len(request.Items) > maxBatchRenameItems {
			return http.StatusBadRequest, fmt.Errorf("批量重命名项目数必须为 1–%d", maxBatchRenameItems)
		}

		response, _ := planBatchRename(d, request.Items)
		if request.DryRun {
			return renderJSON(w, r, response)
		}
		if !response.Valid {
			return renderJSONStatus(w, response, http.StatusConflict)
		}

		batchRenameMu.Lock()
		defer batchRenameMu.Unlock()
		response, plan := planBatchRename(d, request.Items)
		if !response.Valid {
			return renderJSONStatus(w, response, http.StatusConflict)
		}
		if err := assignBatchRenameTemps(d.user.Fs, plan); err != nil {
			response.Valid = false
			response.Error = err.Error()
			return renderJSONStatus(w, response, http.StatusConflict)
		}
		if err := executeBatchRename(r, d, fileCache, plan); err != nil {
			response.Valid = false
			response.Error = err.Error()
			for index := range response.Items {
				response.Items[index].Status = "error"
				response.Items[index].Error = "执行失败，已尝试恢复原名称"
			}
			return renderJSONStatus(w, response, http.StatusInternalServerError)
		}
		response.Executed = true
		for index := range response.Items {
			response.Items[index].Status = "completed"
			recordHistory(d, "file.rename", response.Items[index].To, response.Items[index].From, history.StatusSuccess)
		}
		return renderJSON(w, r, response)
	})
}

func planBatchRename(d *data, requested []batchRenameRequestItem) (batchRenameResponse, []plannedBatchRename) {
	response := batchRenameResponse{
		Valid: true,
		Items: make([]batchRenameResponseItem, len(requested)),
	}
	plan := make([]plannedBatchRename, len(requested))
	sources := make(map[string]int, len(requested))
	destinations := make(map[string]int, len(requested))
	commonDirectory := ""

	for index, item := range requested {
		from := pathmeta.Clean(item.From)
		to := pathmeta.Clean(item.To)
		plan[index] = plannedBatchRename{From: from, To: to}
		response.Items[index] = batchRenameResponseItem{From: from, To: to, Status: "ready"}
		setError := func(message string) {
			response.Valid = false
			response.Items[index].Status = "error"
			response.Items[index].Error = message
		}

		if item.From == "" || item.To == "" || from == "/" || to == "/" {
			setError("源名称和目标名称不能为空或根目录")
			continue
		}
		if from == to {
			setError("新名称与原名称相同")
		}
		fromDirectory := path.Dir(from)
		toDirectory := path.Dir(to)
		if fromDirectory != toDirectory {
			setError("批量重命名只能修改同一目录内的名称")
		}
		if commonDirectory == "" {
			commonDirectory = fromDirectory
		} else if commonDirectory != fromDirectory {
			setError("一次只能重命名同一目录中的项目")
		}
		name := path.Base(to)
		if name == "" || name == "." || name == ".." || strings.ContainsRune(name, '\x00') {
			setError("目标名称无效")
		}
		if !d.Check(from) || !d.Check(to) {
			setError("没有访问源路径或目标路径的权限")
		}
		if previous, exists := sources[from]; exists {
			setError(fmt.Sprintf("源项目与第 %d 项重复", previous+1))
			markBatchRenameError(&response, previous, fmt.Sprintf("源项目与第 %d 项重复", index+1))
		} else {
			sources[from] = index
		}
		if previous, exists := destinations[to]; exists {
			setError(fmt.Sprintf("目标名称与第 %d 项重复", previous+1))
			markBatchRenameError(&response, previous, fmt.Sprintf("目标名称与第 %d 项重复", index+1))
		} else {
			destinations[to] = index
		}
	}

	for index, item := range plan {
		if response.Items[index].Status == "error" {
			continue
		}
		sourceInfo, err := d.user.Fs.Stat(item.From)
		if err != nil {
			if os.IsNotExist(err) {
				markBatchRenameError(&response, index, "源项目不存在")
			} else {
				markBatchRenameError(&response, index, fmt.Sprintf("无法读取源项目: %v", err))
			}
			continue
		}
		destinationInfo, err := d.user.Fs.Stat(item.To)
		if err == nil {
			_, destinationIsSource := sources[item.To]
			if !destinationIsSource && !sameExistingFile(sourceInfo, destinationInfo) {
				markBatchRenameError(&response, index, "目标名称已存在")
			}
		} else if !os.IsNotExist(err) {
			markBatchRenameError(&response, index, fmt.Sprintf("无法检查目标名称: %v", err))
		}
	}
	return response, plan
}

func markBatchRenameError(response *batchRenameResponse, index int, message string) {
	response.Valid = false
	response.Items[index].Status = "error"
	response.Items[index].Error = message
}

func assignBatchRenameTemps(filesystem afero.Fs, plan []plannedBatchRename) error {
	token := make([]byte, 8)
	if _, err := rand.Read(token); err != nil {
		return fmt.Errorf("无法生成安全临时名称: %w", err)
	}
	prefix := ".nas-file-browser-rename-" + hex.EncodeToString(token)
	for index := range plan {
		for attempt := 0; attempt < 100; attempt++ {
			candidate := path.Join(path.Dir(plan[index].From), fmt.Sprintf("%s-%d-%d", prefix, index, attempt))
			if _, err := filesystem.Stat(candidate); os.IsNotExist(err) {
				plan[index].Temp = candidate
				break
			} else if err != nil {
				return err
			}
		}
		if plan[index].Temp == "" {
			return fmt.Errorf("无法为第 %d 项分配临时名称", index+1)
		}
	}
	return nil
}

func executeBatchRename(r *http.Request, d *data, fileCache FileCache, plan []plannedBatchRename) error {
	staged := 0
	for index := range plan {
		if err := patchAction(r.Context(), "rename", plan[index].From, plan[index].Temp, d, fileCache); err != nil {
			return errors.Join(fmt.Errorf("第 %d 项进入临时阶段失败: %w", index+1, err), rollbackStagedBatchRename(r, d, fileCache, plan[:staged]))
		}
		staged++
	}

	completed := 0
	for index := range plan {
		move := func() error {
			return patchAction(r.Context(), "rename", plan[index].Temp, plan[index].To, d, fileCache)
		}
		err := d.RunHook(move, "rename", plan[index].From, plan[index].To, d.user)
		if err == nil {
			completed++
			continue
		}
		if movedToDestination(d.user.Fs, plan[index]) {
			completed++
		}
		return errors.Join(
			fmt.Errorf("第 %d 项写入目标名称失败: %w", index+1, err),
			rollbackCompletedBatchRename(r, d, fileCache, plan, completed),
		)
	}
	return nil
}

func movedToDestination(filesystem afero.Fs, item plannedBatchRename) bool {
	_, tempErr := filesystem.Stat(item.Temp)
	_, destinationErr := filesystem.Stat(item.To)
	return os.IsNotExist(tempErr) && destinationErr == nil
}

func rollbackStagedBatchRename(r *http.Request, d *data, fileCache FileCache, staged []plannedBatchRename) error {
	var rollbackErr error
	for index := len(staged) - 1; index >= 0; index-- {
		rollbackErr = errors.Join(rollbackErr, patchAction(r.Context(), "rename", staged[index].Temp, staged[index].From, d, fileCache))
	}
	return rollbackErr
}

func rollbackCompletedBatchRename(r *http.Request, d *data, fileCache FileCache, plan []plannedBatchRename, completed int) error {
	var rollbackErr error
	for index := completed - 1; index >= 0; index-- {
		rollbackErr = errors.Join(rollbackErr, patchAction(r.Context(), "rename", plan[index].To, plan[index].From, d, fileCache))
	}
	for index := len(plan) - 1; index >= completed; index-- {
		if _, err := d.user.Fs.Stat(plan[index].Temp); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			rollbackErr = errors.Join(rollbackErr, err)
			continue
		}
		rollbackErr = errors.Join(rollbackErr, patchAction(r.Context(), "rename", plan[index].Temp, plan[index].From, d, fileCache))
	}
	return rollbackErr
}
