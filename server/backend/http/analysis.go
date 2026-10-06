package fbhttp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/analysis"
	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

const maxAnalysisScopes = 32
const (
	defaultRecentAnalysisLimit = 6
	maxRecentAnalysisLimit     = 10
)

var analysisWorkerSlot = make(chan struct{}, 1)
var errAnalysisScopeForbidden = errors.New("没有访问分析路径的权限")

type duplicateAnalysisArgs struct {
	Paths []string `json:"paths"`
}

type storageAnalysisArgs struct {
	Paths []string `json:"paths"`
}

type analysisRecentMetrics struct {
	ScannedFiles       int   `json:"scannedFiles"`
	ScannedDirectories int   `json:"scannedDirectories"`
	ScannedBytes       int64 `json:"scannedBytes"`
	DuplicateGroups    int   `json:"duplicateGroups"`
	ReclaimableBytes   int64 `json:"reclaimableBytes"`
}

type analysisRecentItem struct {
	ID             string                 `json:"id"`
	Tool           string                 `json:"tool"`
	Status         tasks.Status           `json:"status"`
	CreatedAt      int64                  `json:"createdAt"`
	FinishedAt     int64                  `json:"finishedAt,omitempty"`
	Scopes         []string               `json:"scopes"`
	ProcessedItems int                    `json:"processedItems"`
	TotalItems     int                    `json:"totalItems"`
	Error          string                 `json:"error,omitempty"`
	ResultReady    bool                   `json:"resultReady"`
	Metrics        *analysisRecentMetrics `json:"metrics,omitempty"`
}

type analysisRecentResponse struct {
	Items      []analysisRecentItem `json:"items"`
	NextCursor string               `json:"nextCursor,omitempty"`
}

func duplicateAnalysisStartHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download {
			return http.StatusForbidden, fmt.Errorf("没有读取文件内容的权限")
		}
		var request duplicateAnalysisArgs
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("重复文件扫描参数无效: %w", err)
		}
		paths, err := validateAnalysisScopes(d, request.Paths)
		if err != nil {
			if errors.Is(err, errAnalysisScopeForbidden) {
				return http.StatusForbidden, err
			}
			return http.StatusBadRequest, err
		}
		args, err := json.Marshal(duplicateAnalysisArgs{Paths: paths})
		if err != nil {
			return http.StatusInternalServerError, err
		}
		task, err := enqueueTask(runtime, d, d.user, tasks.TypeDuplicateAnalysis, "查找重复文件", args, "")
		if err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "analysis.duplicates", strings.Join(paths, "、"), task.ID, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

func storageAnalysisStartHandler(runtime *tasks.Runtime) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		if !d.user.Perm.Download {
			return http.StatusForbidden, fmt.Errorf("没有读取文件元数据的权限")
		}
		var request storageAnalysisArgs
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("存储分析参数无效: %w", err)
		}
		paths, err := validateAnalysisScopes(d, request.Paths)
		if err != nil {
			if errors.Is(err, errAnalysisScopeForbidden) {
				return http.StatusForbidden, err
			}
			return http.StatusBadRequest, err
		}
		args, err := json.Marshal(storageAnalysisArgs{Paths: paths})
		if err != nil {
			return http.StatusInternalServerError, err
		}
		task, err := enqueueTask(runtime, d, d.user, tasks.TypeStorageAnalysis, "分析存储空间", args, "")
		if err != nil {
			return taskErrorStatus(err), err
		}
		recordHistory(d, "analysis.storage", strings.Join(paths, "、"), task.ID, history.StatusSubmitted)
		return renderJSONStatus(w, task, http.StatusAccepted)
	})
}

var analysisResultHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	task, err := d.store.Tasks.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return taskErrorStatus(err), err
	}
	if task.Type != tasks.TypeDuplicateAnalysis {
		return http.StatusNotFound, tasks.ErrNotExist
	}
	if task.Status != tasks.StatusCompleted || len(task.Result) == 0 {
		return http.StatusConflict, fmt.Errorf("分析结果尚未完成")
	}
	var report analysis.DuplicateReport
	if err := json.Unmarshal(task.Result, &report); err != nil {
		return http.StatusInternalServerError, fmt.Errorf("分析结果损坏: %w", err)
	}
	return renderJSON(w, r, &report)
})

var storageAnalysisResultHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	task, err := d.store.Tasks.Get(d.user.ID, mux.Vars(r)["id"], d.user.Perm.Admin)
	if err != nil {
		return taskErrorStatus(err), err
	}
	if task.Type != tasks.TypeStorageAnalysis {
		return http.StatusNotFound, tasks.ErrNotExist
	}
	if task.Status != tasks.StatusCompleted || len(task.Result) == 0 {
		return http.StatusConflict, fmt.Errorf("存储分析结果尚未完成")
	}
	var report analysis.StorageReport
	if err := json.Unmarshal(task.Result, &report); err != nil {
		return http.StatusInternalServerError, fmt.Errorf("存储分析结果损坏: %w", err)
	}
	return renderJSON(w, r, &report)
})

var analysisRecentHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	tool := r.URL.Query().Get("tool")
	taskType := tasks.TypeDuplicateAnalysis
	if tool == "storage" {
		taskType = tasks.TypeStorageAnalysis
	} else if tool != "" && tool != "duplicates" {
		return http.StatusBadRequest, fmt.Errorf("存储工具类型无效")
	}
	limit := defaultRecentAnalysisLimit
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed <= 0 || parsed > maxRecentAnalysisLimit {
			return http.StatusBadRequest, fmt.Errorf("最近扫描数量必须为 1–%d", maxRecentAnalysisLimit)
		}
		limit = parsed
	}
	var cursor *taskCursor
	if raw := strings.TrimSpace(r.URL.Query().Get("cursor")); raw != "" {
		decoded, err := decodeTaskCursor(raw)
		if err != nil {
			return http.StatusBadRequest, fmt.Errorf("cursor 无效")
		}
		cursor = decoded
	}
	// 分析报告始终只属于发起用户。管理员需要查看其他用户任务时使用任务中心，
	// 此处不能因为管理员权限而混入无法从当前工作区打开的其他用户报告。
	var taskCursorValue *tasks.RecentCursor
	if cursor != nil {
		taskCursorValue = &tasks.RecentCursor{CreatedAt: cursor.CreatedAt, ID: cursor.ID}
	}
	loaded, err := d.store.Tasks.ListRecent(d.user.ID, taskType, limit+1, taskCursorValue)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	items := make([]analysisRecentItem, 0, limit)
	for _, task := range loaded {
		items = append(items, summarizeAnalysisTask(task))
	}
	response := analysisRecentResponse{Items: items}
	if len(items) > limit {
		response.Items = items[:limit]
		response.NextCursor = encodeTaskCursor(loaded[limit-1])
	}
	if response.Items == nil {
		response.Items = []analysisRecentItem{}
	}
	return renderJSON(w, r, response)
})

func summarizeAnalysisTask(task *tasks.Task) analysisRecentItem {
	item := analysisRecentItem{
		ID: task.ID, Tool: "duplicates", Status: task.Status,
		CreatedAt: task.CreatedAt, FinishedAt: task.FinishedAt,
		Scopes: make([]string, 0), ProcessedItems: task.ProcessedItems,
		TotalItems: task.TotalItems, Error: task.Error,
	}
	if task.Type == tasks.TypeStorageAnalysis {
		item.Tool = "storage"
		var args storageAnalysisArgs
		if json.Unmarshal(task.Args, &args) == nil {
			item.Scopes = append(item.Scopes, args.Paths...)
		}
		if task.Status == tasks.StatusCompleted && len(task.Result) > 0 {
			var report analysis.StorageReport
			if json.Unmarshal(task.Result, &report) == nil {
				item.ResultReady = true
				item.Metrics = &analysisRecentMetrics{
					ScannedFiles: report.ScannedFiles, ScannedDirectories: report.ScannedDirectories,
					ScannedBytes: report.ScannedBytes,
				}
			}
		}
		return item
	}
	var args duplicateAnalysisArgs
	if json.Unmarshal(task.Args, &args) == nil {
		item.Scopes = append(item.Scopes, args.Paths...)
	}
	if task.Status == tasks.StatusCompleted && len(task.Result) > 0 {
		var report analysis.DuplicateReport
		if json.Unmarshal(task.Result, &report) == nil {
			item.ResultReady = true
			item.Metrics = &analysisRecentMetrics{
				ScannedFiles: report.ScannedFiles, ScannedBytes: report.ScannedBytes,
				DuplicateGroups: report.DuplicateGroups, ReclaimableBytes: report.ReclaimableBytes,
			}
		}
	}
	return item
}

func validateAnalysisScopes(d *data, requested []string) ([]string, error) {
	if len(requested) == 0 || len(requested) > maxAnalysisScopes {
		return nil, fmt.Errorf("分析范围必须包含 1–%d 个路径", maxAnalysisScopes)
	}
	unique := make(map[string]struct{}, len(requested))
	paths := make([]string, 0, len(requested))
	for _, requestedPath := range requested {
		if requestedPath == "" {
			return nil, fmt.Errorf("分析路径不能为空")
		}
		cleaned := pathmeta.Clean(requestedPath)
		if !d.Check(cleaned) {
			return nil, fmt.Errorf("%w: %s", errAnalysisScopeForbidden, cleaned)
		}
		if _, err := d.user.Fs.Stat(cleaned); err != nil {
			return nil, fmt.Errorf("无法读取分析路径 %s", cleaned)
		}
		if _, exists := unique[cleaned]; exists {
			continue
		}
		unique[cleaned] = struct{}{}
		paths = append(paths, cleaned)
	}
	sort.Slice(paths, func(i, j int) bool {
		if len(paths[i]) != len(paths[j]) {
			return len(paths[i]) < len(paths[j])
		}
		return paths[i] < paths[j]
	})
	minimal := make([]string, 0, len(paths))
	for _, candidate := range paths {
		covered := false
		for _, root := range minimal {
			if root == "/" || candidate == root || strings.HasPrefix(candidate, root+"/") {
				covered = true
				break
			}
		}
		if !covered {
			minimal = append(minimal, candidate)
		}
	}
	return minimal, nil
}

func duplicateAnalysisRunner(d *data, task *tasks.Task, args duplicateAnalysisArgs) tasks.Runner {
	return func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		select {
		case analysisWorkerSlot <- struct{}{}:
			defer func() { <-analysisWorkerSlot }()
		case <-ctx.Done():
			return nil, ctx.Err()
		}

		lastReported := -16
		result, err := analysis.FindDuplicates(ctx, d.user.Fs, args.Paths, d, func(progress analysis.ScanProgress) error {
			if progress.ProcessedItems != progress.TotalItems && progress.ProcessedItems-lastReported < 16 {
				return nil
			}
			lastReported = progress.ProcessedItems
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

func storageAnalysisRunner(d *data, task *tasks.Task, args storageAnalysisArgs) tasks.Runner {
	return func(ctx context.Context, report tasks.Reporter) (json.RawMessage, error) {
		select {
		case analysisWorkerSlot <- struct{}{}:
			defer func() { <-analysisWorkerSlot }()
		case <-ctx.Done():
			return nil, ctx.Err()
		}

		lastReported := -64
		result, err := analysis.AnalyzeStorage(ctx, d.user.Fs, args.Paths, d, func(progress analysis.ScanProgress) error {
			if progress.ProcessedItems-lastReported < 64 && progress.ProcessedItems != 0 {
				return nil
			}
			lastReported = progress.ProcessedItems
			return report(tasks.Progress{
				ProcessedItems: progress.ProcessedItems,
				ProcessedBytes: progress.ProcessedBytes,
			})
		})
		if err != nil {
			return nil, err
		}
		if err := report(tasks.Progress{
			TotalItems: result.ScannedFiles + result.ScannedDirectories, ProcessedItems: result.ScannedFiles + result.ScannedDirectories,
			TotalBytes: result.ScannedBytes, ProcessedBytes: result.ScannedBytes,
		}); err != nil {
			return nil, err
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			return nil, err
		}
		return encoded, nil
	}
}
