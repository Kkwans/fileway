package fbhttp

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"time"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/analysis"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/trash"
	"github.com/spf13/afero"
)

const duplicateCleanupSchemaVersion = 3

type duplicateCleanupSelection struct {
	SHA256   string `json:"sha256"`
	KeepPath string `json:"keepPath"`
}
type duplicateCleanupRequest struct {
	Groups []duplicateCleanupSelection `json:"groups"`
}
type duplicateCleanupArgs struct {
	ReportID   string                      `json:"reportId"`
	Groups     []duplicateCleanupSelection `json:"groups"`
	ResumeFrom string                      `json:"resumeFrom,omitempty"`
}
type duplicateCleanupFileResult struct {
	Path    string `json:"path"`
	Status  string `json:"status"`
	TrashID string `json:"trashId,omitempty"`
	Reason  string `json:"reason,omitempty"`
}
type duplicateCleanupGroupResult struct {
	SHA256   string                       `json:"sha256"`
	KeepPath string                       `json:"keepPath"`
	Files    []duplicateCleanupFileResult `json:"files"`
}
type duplicateCleanupResult struct {
	ReportID string                        `json:"reportId"`
	Groups   []duplicateCleanupGroupResult `json:"groups"`
}

func duplicateCleanupStartHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Delete {
			return http.StatusForbidden, fmt.Errorf("没有将重复文件移入回收站的权限")
		}
		var request duplicateCleanupRequest
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("清理参数无效: %w", err)
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			return http.StatusBadRequest, fmt.Errorf("清理参数只能包含一个 JSON 对象")
		}
		task, status, err := enqueueDuplicateCleanup(runtime, d, mux.Vars(r)["id"], request.Groups, "", false)
		if err != nil {
			return status, err
		}
		recordHistory(d, "analysis.duplicates.cleanup", "重复文件报告 "+mux.Vars(r)["id"], task.ID, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

var duplicateCleanupResultHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	task, err := d.store.Tasks.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return taskErrorStatus(err), err
	}
	if task.Type != tasks.TypeDuplicateCleanup {
		return http.StatusNotFound, tasks.ErrNotExist
	}
	if len(task.Result) == 0 {
		return http.StatusConflict, fmt.Errorf("清理任务尚无结果")
	}
	var result duplicateCleanupResult
	if err := json.Unmarshal(task.Result, &result); err != nil {
		return http.StatusInternalServerError, fmt.Errorf("清理结果损坏: %w", err)
	}
	return renderJSON(w, r, &result)
})

var duplicateCleanupForReportHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	all, err := d.store.Tasks.List(d.user.ID, false)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	reportID := mux.Vars(r)["id"]
	var latest *tasks.Task
	for _, task := range all {
		if task.Type != tasks.TypeDuplicateCleanup {
			continue
		}
		var args duplicateCleanupArgs
		if json.Unmarshal(task.Args, &args) != nil || args.ReportID != reportID {
			continue
		}
		if latest == nil || task.CreatedAt > latest.CreatedAt {
			latest = task
		}
	}
	if latest == nil {
		return http.StatusNotFound, tasks.ErrNotExist
	}
	return renderJSON(w, r, latest)
})

func enqueueDuplicateCleanup(runtime *tasks.Runtime, d *data, reportID string, selections []duplicateCleanupSelection, retryOf string, isRetry bool) (*tasks.Task, int, error) {
	reportTask, err := d.store.Tasks.Get(d.user.ID, reportID, false)
	if err != nil {
		return nil, taskErrorStatus(err), err
	}
	if reportTask.Type != tasks.TypeDuplicateAnalysis || reportTask.Status != tasks.StatusCompleted || len(reportTask.Result) == 0 {
		return nil, http.StatusConflict, fmt.Errorf("重复文件报告尚不可用于清理")
	}
	var report analysis.DuplicateReport
	if err := json.Unmarshal(reportTask.Result, &report); err != nil {
		return nil, http.StatusInternalServerError, fmt.Errorf("重复文件报告损坏: %w", err)
	}
	if report.SchemaVersion != duplicateCleanupSchemaVersion {
		return nil, http.StatusConflict, fmt.Errorf("该报告缺少安全清理元数据，请重新扫描")
	}
	normalized, keys, err := validateCleanupSelections(d, &report, selections)
	if err != nil {
		return nil, http.StatusBadRequest, err
	}
	if !isRetry {
		all, err := d.store.Tasks.List(d.user.ID, false)
		if err != nil {
			return nil, http.StatusInternalServerError, err
		}
		for _, existing := range all {
			if existing.Type == tasks.TypeDuplicateCleanup {
				var args duplicateCleanupArgs
				if json.Unmarshal(existing.Args, &args) == nil && args.ReportID == reportID {
					return nil, http.StatusConflict, fmt.Errorf("该报告已有清理任务，请查看原任务或使用重试")
				}
			}
		}
	}
	args := duplicateCleanupArgs{ReportID: reportID, Groups: normalized, ResumeFrom: retryOf}
	raw, err := json.Marshal(args)
	if err != nil {
		return nil, http.StatusInternalServerError, err
	}
	task, err := d.store.Tasks.New(d.user.ID, d.user.Username, tasks.TypeDuplicateCleanup, "清理重复文件", raw, retryOf)
	if err != nil {
		return nil, http.StatusInternalServerError, err
	}
	runner := duplicateCleanupRunner(d, task, report, args)
	keys = append(keys, "duplicate-cleanup-report:"+reportID)
	if err := runtime.StartExclusive(task, runner, keys...); err != nil {
		task.Status = tasks.StatusFailed
		task.Error = err.Error()
		task.FinishedAt = time.Now().UnixMilli()
		_ = d.store.Tasks.Update(task)
		return nil, taskErrorStatus(err), err
	}
	return task, http.StatusAccepted, nil
}

func validateCleanupSelections(d *data, report *analysis.DuplicateReport, selections []duplicateCleanupSelection) ([]duplicateCleanupSelection, []string, error) {
	if len(selections) == 0 || len(selections) > len(report.Groups) {
		return nil, nil, fmt.Errorf("至少选择一个完整重复组")
	}
	groups := make(map[string]analysis.DuplicateGroup, len(report.Groups))
	for _, group := range report.Groups {
		groups[group.SHA256] = group
	}
	seen := make(map[string]struct{}, len(selections))
	seenPaths := make(map[string]struct{})
	normalized := make([]duplicateCleanupSelection, 0, len(selections))
	keys := make([]string, 0)
	for _, selection := range selections {
		if _, ok := seen[selection.SHA256]; ok {
			return nil, nil, fmt.Errorf("重复提交了同一重复组")
		}
		seen[selection.SHA256] = struct{}{}
		group, ok := groups[selection.SHA256]
		if !ok {
			return nil, nil, fmt.Errorf("报告中不存在所选重复组")
		}
		if len(group.Files) != group.TotalFiles || group.TotalFiles < 2 {
			return nil, nil, fmt.Errorf("截断或不完整的重复组不能清理")
		}
		keepFound := false
		for _, file := range group.Files {
			if _, exists := seenPaths[file.Path]; exists {
				return nil, nil, fmt.Errorf("重复报告包含重叠文件，不能安全清理")
			}
			seenPaths[file.Path] = struct{}{}
			if file.Path == selection.KeepPath {
				keepFound = true
			}
			if file.Identity == nil || file.Identity.Links != 1 || !file.Identity.IsRegular() {
				return nil, nil, fmt.Errorf("包含符号链接、硬链接或身份缺失的重复组不能清理")
			}
			if !d.Check(file.Path) {
				return nil, nil, fmt.Errorf("重复组包含不可访问路径")
			}
			keys = append(keys, "duplicate-cleanup-path:"+file.Path)
		}
		if !keepFound {
			return nil, nil, fmt.Errorf("保留项不属于所选重复组")
		}
		normalized = append(normalized, duplicateCleanupSelection{SHA256: selection.SHA256, KeepPath: selection.KeepPath})
	}
	sort.Slice(normalized, func(i, j int) bool { return normalized[i].SHA256 < normalized[j].SHA256 })
	return normalized, keys, nil
}

func duplicateCleanupRunner(d *data, task *tasks.Task, report analysis.DuplicateReport, args duplicateCleanupArgs) tasks.Runner {
	return func(ctx context.Context, reportProgress tasks.Reporter) (json.RawMessage, error) {
		groups := make(map[string]analysis.DuplicateGroup, len(report.Groups))
		for _, group := range report.Groups {
			groups[group.SHA256] = group
		}
		result := duplicateCleanupResult{ReportID: args.ReportID, Groups: make([]duplicateCleanupGroupResult, 0, len(args.Groups))}
		previous := loadPreviousCleanupResult(d, task.UserID, args.ResumeFrom)
		total := 0
		for _, selection := range args.Groups {
			total += groups[selection.SHA256].TotalFiles - 1
		}
		progress := tasks.Progress{TotalItems: total}
		if err := reportProgress(progress); err != nil {
			return nil, err
		}
		failures := 0
		for _, selection := range args.Groups {
			if err := ctx.Err(); err != nil {
				return cleanupCheckpoint(result), err
			}
			group := groups[selection.SHA256]
			groupResult := duplicateCleanupGroupResult{SHA256: selection.SHA256, KeepPath: selection.KeepPath, Files: make([]duplicateCleanupFileResult, 0, group.TotalFiles-1)}
			byPath := make(map[string]analysis.DuplicateFile, len(group.Files))
			for _, file := range group.Files {
				byPath[file.Path] = file
			}
			preflightErr := verifyCleanupGroup(ctx, d, group, previous)
			for _, file := range group.Files {
				if file.Path == selection.KeepPath {
					continue
				}
				if err := ctx.Err(); err != nil {
					result.Groups = append(result.Groups, groupResult)
					return cleanupCheckpoint(result), err
				}
				outcome := duplicateCleanupFileResult{Path: file.Path}
				if prior, ok := previous[file.Path]; ok && completedCleanupOutcome(prior) {
					outcome = reconcileCleanupResult(d, prior)
					if outcome.Status == "failed" {
						failures++
					}
				} else if preflightErr != nil {
					outcome.Status, outcome.Reason = "failed", preflightErr.Error()
					failures++
				} else if err := verifyCleanupFile(ctx, d, byPath[selection.KeepPath], group.SHA256); err != nil {
					outcome.Status, outcome.Reason = "failed", "保留项已变化，已停止该组"
					failures++
					preflightErr = err
				} else if err := verifyCleanupFile(ctx, d, file, group.SHA256); err != nil {
					outcome.Status, outcome.Reason = "failed", "待清理文件已变化或不可访问"
					failures++
				} else {
					info, err := files.NewFileInfo(&files.FileOptions{Fs: d.user.Fs, Path: file.Path, Modify: d.user.Perm.Modify, Expand: false, ReadHeader: d.server.TypeDetectionByHeader, Checker: d})
					if err == nil {
						var item *trash.Item
						item, _, err = moveResourceToTrashItem(ctx, d, d.fileCache, info)
						if item != nil {
							outcome.TrashID = item.ID
						}
					}
					if err != nil {
						outcome.Status, outcome.Reason = "failed", "移入回收站失败"
						failures++
					} else {
						outcome.Status = "success"
					}
				}
				groupResult.Files = append(groupResult.Files, outcome)
				progress.ProcessedItems++
				progress.Checkpoint = cleanupCheckpointWithGroup(result, groupResult)
				if err := reportProgress(progress); err != nil {
					return progress.Checkpoint, err
				}
			}
			result.Groups = append(result.Groups, groupResult)
		}
		raw := cleanupCheckpoint(result)
		if failures > 0 {
			return raw, fmt.Errorf("%d 个文件未完成清理", failures)
		}
		return raw, nil
	}
}

func verifyCleanupGroup(ctx context.Context, d *data, group analysis.DuplicateGroup, previous map[string]duplicateCleanupFileResult) error {
	if len(group.Files) != group.TotalFiles {
		return fmt.Errorf("重复组结果不完整")
	}
	for _, file := range group.Files {
		if completedCleanupOutcome(previous[file.Path]) {
			continue
		}
		if err := verifyCleanupFile(ctx, d, file, group.SHA256); err != nil {
			return fmt.Errorf("重复组成员已变化，未执行该组")
		}
	}
	return nil
}
func verifyCleanupFile(ctx context.Context, d *data, expected analysis.DuplicateFile, wantHash string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !d.Check(expected.Path) {
		return fmt.Errorf("路径权限已变化")
	}
	current := files.FileIdentity(d.user.Fs, expected.Path)
	if expected.Identity == nil || current == nil || current.Links != 1 || !current.IsRegular() || !expected.Identity.Same(current) {
		return fmt.Errorf("文件身份已变化")
	}
	info, err := d.user.Fs.Stat(expected.Path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != expected.Size || info.ModTime().UnixMilli() != expected.Modified {
		return fmt.Errorf("文件元数据已变化")
	}
	hash, err := hashCleanupFile(ctx, d.user.Fs, expected.Path)
	if err != nil || hash != wantHash {
		return fmt.Errorf("文件内容已变化")
	}
	after := files.FileIdentity(d.user.Fs, expected.Path)
	if !current.Same(after) {
		return fmt.Errorf("校验期间文件身份已变化")
	}
	return nil
}
func hashCleanupFile(ctx context.Context, fs afero.Fs, path string) (string, error) {
	handle, err := fs.Open(path)
	if err != nil {
		return "", err
	}
	defer func() { _ = handle.Close() }()
	hash := sha256.New()
	buffer := make([]byte, 128*1024)
	for {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		read, readErr := handle.Read(buffer)
		if read > 0 {
			_, _ = hash.Write(buffer[:read])
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return "", readErr
		}
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
func cleanupCheckpoint(result duplicateCleanupResult) json.RawMessage {
	raw, _ := json.Marshal(result)
	return raw
}
func cleanupCheckpointWithGroup(result duplicateCleanupResult, group duplicateCleanupGroupResult) json.RawMessage {
	copyResult := result
	copyResult.Groups = append(append([]duplicateCleanupGroupResult(nil), result.Groups...), group)
	return cleanupCheckpoint(copyResult)
}
func loadPreviousCleanupResult(d *data, userID uint, id string) map[string]duplicateCleanupFileResult {
	results := make(map[string]duplicateCleanupFileResult)
	if id == "" {
		return results
	}
	task, err := d.store.Tasks.Get(userID, id, false)
	if err != nil {
		return results
	}
	var previous duplicateCleanupResult
	if json.Unmarshal(task.Result, &previous) != nil {
		return results
	}
	for _, group := range previous.Groups {
		for _, file := range group.Files {
			results[file.Path] = file
		}
	}
	return results
}
func reconcileCleanupResult(d *data, prior duplicateCleanupFileResult) duplicateCleanupFileResult {
	if prior.TrashID != "" {
		if item, err := d.store.Trash.Get(d.user.ID, prior.TrashID, false); err == nil && item.OriginalPath == prior.Path {
			return duplicateCleanupFileResult{Path: prior.Path, Status: "skipped", TrashID: prior.TrashID, Reason: "先前已完成，文件仍在回收站"}
		}
	}
	exists, err := afero.Exists(d.user.Fs, prior.Path)
	if err == nil && !exists {
		return duplicateCleanupFileResult{Path: prior.Path, Status: "skipped", Reason: "先前已完成，源路径当前不存在"}
	}
	return duplicateCleanupFileResult{Path: prior.Path, Status: "failed", Reason: "先前结果已恢复或变化，未重复清理"}
}

func completedCleanupOutcome(result duplicateCleanupFileResult) bool {
	return result.Status == "success" || result.Status == "skipped"
}
