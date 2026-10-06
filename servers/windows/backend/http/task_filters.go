package fbhttp

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/mux"

	"github.com/Kkwans/nas-file-browser/backend/history"
	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
)

const (
	defaultTaskPageSize = 30
	maxTaskPageSize     = 100
)

type taskFilterSnapshot struct {
	Statuses []tasks.Status `json:"statuses,omitempty"`
	User     string         `json:"user,omitempty"`
	Type     tasks.Type     `json:"type,omitempty"`
	Archived *bool          `json:"archived,omitempty"`
	Text     string         `json:"text,omitempty"`
	From     int64          `json:"from,omitempty"`
	To       int64          `json:"to,omitempty"`
	Category string         `json:"category,omitempty"`
}

type taskListCounts struct {
	All       int `json:"all"`
	Active    int `json:"active"`
	Attention int `json:"attention"`
	Canceled  int `json:"canceled"`
	Completed int `json:"completed"`
	Archived  int `json:"archived"`
}

type taskListResponse struct {
	Items          []*tasks.Task             `json:"items"`
	NextCursor     string                    `json:"nextCursor,omitempty"`
	Total          int                       `json:"total"`
	Counts         taskListCounts            `json:"counts"`
	CategoryCounts map[string]taskListCounts `json:"categoryCounts"`
	Owners         []string                  `json:"owners"`
}

type taskCursor struct {
	CreatedAt int64  `json:"createdAt"`
	ID        string `json:"id"`
}

type taskBatchRequest struct {
	Action        string             `json:"action"`
	Filters       taskFilterSnapshot `json:"filters"`
	ExpectedCount int                `json:"expectedCount"`
}

type taskBatchFailure struct {
	ID    string `json:"id"`
	Error string `json:"error"`
}

type taskBatchResponse struct {
	Matched   int                `json:"matched"`
	Succeeded int                `json:"succeeded"`
	Skipped   int                `json:"skipped"`
	Actual    int                `json:"actualCount"`
	Created   []*tasks.Task      `json:"created,omitempty"`
	Failures  []taskBatchFailure `json:"failures,omitempty"`
}

type taskDeleteRecordsResponse struct {
	Deleted int `json:"deleted"`
}

var taskListHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	filter, limit, cursor, err := taskFilterFromQuery(r)
	if err != nil {
		return http.StatusBadRequest, err
	}
	all, err := d.store.Tasks.List(d.user.ID, d.user.Perm.Admin)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	baseFilter := filter
	baseFilter.Statuses = nil
	baseFilter.Archived = nil
	baseFilter.Category = ""
	base := filterTasks(all, baseFilter)
	matched := filterTasks(all, filter)
	owners := taskOwners(matched)
	total := len(matched)
	if cursor != nil {
		matched = tasksAfterCursor(matched, *cursor)
	}

	categoryCounts := map[string]taskListCounts{}
	for _, category := range []string{"file", "background"} {
		categoryFilter := baseFilter
		categoryFilter.Category = category
		categoryCounts[category] = summarizeTasks(filterTasks(all, categoryFilter))
	}
	response := taskListResponse{
		Total:          total,
		Counts:         summarizeTasks(base),
		CategoryCounts: categoryCounts,
		Owners:         owners,
	}
	if len(matched) > limit {
		response.Items = matched[:limit]
		response.NextCursor = encodeTaskCursor(response.Items[len(response.Items)-1])
	} else {
		response.Items = matched
	}
	if response.Items == nil {
		response.Items = []*tasks.Task{}
	}
	return renderJSON(w, r, response)
})

// taskDeleteRecordsHandler clears a category's terminal records for the
// authenticated user. "Delete" here follows the existing task-center archive
// semantics: records leave the active list but remain recoverable in the
// archived task history. Active work is never touched by this endpoint.
var taskDeleteRecordsHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	category := strings.TrimSpace(r.URL.Query().Get("category"))
	if category != "file" && category != "background" {
		return http.StatusBadRequest, fmt.Errorf("清空任务记录必须指定 category=file 或 category=background")
	}
	all, err := d.store.Tasks.List(d.user.ID, false)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	now := time.Now().UnixMilli()
	deleted := 0
	for _, task := range all {
		if task.ArchivedAt != 0 || !taskCategoryMatches(task, category) || !task.CanArchive() {
			continue
		}
		task.ArchivedAt = now
		if err := d.store.Tasks.Update(task); err != nil {
			return http.StatusInternalServerError, err
		}
		deleted++
	}
	if deleted > 0 {
		recordHistory(d, "task.batch.archive", fmt.Sprintf("%s任务记录", category), fmt.Sprintf("%d 条", deleted), history.StatusSuccess)
	}
	return renderJSON(w, r, taskDeleteRecordsResponse{Deleted: deleted})
})

func taskCategoryMatches(task *tasks.Task, category string) bool {
	if task == nil {
		return false
	}
	fileTask := task.Type == tasks.TypeFileCopy || task.Type == tasks.TypeFileMove
	return category == "file" && fileTask || category == "background" && !fileTask
}

func taskArchiveHandler(archive bool) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		task, err := d.store.Tasks.Get(d.user.ID, muxTaskID(r), d.user.Perm.Admin)
		if err != nil {
			return taskErrorStatus(err), err
		}
		if archive {
			if !task.CanArchive() {
				return http.StatusConflict, tasks.ErrState
			}
			task.ArchivedAt = time.Now().UnixMilli()
		} else {
			if task.ArchivedAt == 0 {
				return http.StatusConflict, tasks.ErrState
			}
			task.ArchivedAt = 0
		}
		if err := d.store.Tasks.Update(task); err != nil {
			return http.StatusInternalServerError, err
		}
		action := "task.archive"
		if !archive {
			action = "task.unarchive"
		}
		recordHistory(d, action, task.Title, task.ID, history.StatusSuccess)
		return renderJSON(w, r, task)
	})
}

func taskBatchHandler(runtime *tasks.Runtime, hlsServices ...*hls.Service) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		var request taskBatchRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			return http.StatusBadRequest, fmt.Errorf("无效的批量任务请求: %w", err)
		}
		if request.ExpectedCount < 0 {
			return http.StatusBadRequest, fmt.Errorf("expectedCount 不能为负数")
		}
		if request.Action != "retry" && request.Action != "archive" && request.Action != "unarchive" {
			return http.StatusBadRequest, fmt.Errorf("不支持的批量动作 %q", request.Action)
		}
		if err := validateTaskFilter(request.Filters); err != nil {
			return http.StatusBadRequest, err
		}
		all, err := d.store.Tasks.List(d.user.ID, d.user.Perm.Admin)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		matched := filterTasks(all, request.Filters)
		if len(matched) != request.ExpectedCount {
			return renderJSONStatus(w, taskBatchResponse{Matched: request.ExpectedCount, Actual: len(matched)}, http.StatusConflict)
		}
		for _, task := range matched {
			valid := request.Action == "retry" && task.CanRetry() ||
				request.Action == "archive" && task.CanArchive() ||
				request.Action == "unarchive" && task.ArchivedAt != 0
			if !valid {
				return renderJSONStatus(w, taskBatchResponse{
					Matched: len(matched), Actual: len(matched),
					Failures: []taskBatchFailure{{ID: task.ID, Error: tasks.ErrState.Error()}},
				}, http.StatusConflict)
			}
		}

		response := taskBatchResponse{Matched: len(matched)}
		now := time.Now().UnixMilli()
		for _, task := range matched {
			switch request.Action {
			case "archive":
				if !task.CanArchive() {
					response.Skipped++
					continue
				}
				task.ArchivedAt = now
				if err := d.store.Tasks.Update(task); err != nil {
					response.Failures = append(response.Failures, taskBatchFailure{ID: task.ID, Error: err.Error()})
					continue
				}
				response.Succeeded++
			case "unarchive":
				if task.ArchivedAt == 0 {
					response.Skipped++
					continue
				}
				task.ArchivedAt = 0
				if err := d.store.Tasks.Update(task); err != nil {
					response.Failures = append(response.Failures, taskBatchFailure{ID: task.ID, Error: err.Error()})
					continue
				}
				response.Succeeded++
			case "retry":
				retry, _, err := retryExistingTask(runtime, d, task, hlsServices...)
				if err != nil {
					response.Failures = append(response.Failures, taskBatchFailure{ID: task.ID, Error: err.Error()})
					continue
				}
				response.Created = append(response.Created, retry)
				response.Succeeded++
			}
		}
		status := history.StatusSuccess
		if len(response.Failures) > 0 || response.Skipped > 0 {
			status = history.StatusFailed
		}
		recordHistory(d, "task.batch."+request.Action, fmt.Sprintf("%d 项任务", len(matched)), "", status)
		return renderJSON(w, r, response)
	})
}

func taskFilterFromQuery(r *http.Request) (taskFilterSnapshot, int, *taskCursor, error) {
	query := r.URL.Query()
	filter := taskFilterSnapshot{
		User:     strings.TrimSpace(query.Get("user")),
		Type:     tasks.Type(strings.TrimSpace(query.Get("type"))),
		Text:     strings.TrimSpace(query.Get("text")),
		Category: strings.TrimSpace(query.Get("category")),
	}
	archived := false
	filter.Archived = &archived
	if raw := strings.TrimSpace(query.Get("archived")); raw != "" {
		value, err := strconv.ParseBool(raw)
		if err != nil {
			return filter, 0, nil, fmt.Errorf("archived 必须是 true 或 false")
		}
		filter.Archived = &value
	}
	if raw := strings.TrimSpace(query.Get("status")); raw != "" {
		for _, value := range strings.Split(raw, ",") {
			filter.Statuses = append(filter.Statuses, tasks.Status(strings.TrimSpace(value)))
		}
	}
	var err error
	if filter.From, err = parseTaskTimestamp(query.Get("from")); err != nil {
		return filter, 0, nil, fmt.Errorf("from 无效: %w", err)
	}
	if filter.To, err = parseTaskTimestamp(query.Get("to")); err != nil {
		return filter, 0, nil, fmt.Errorf("to 无效: %w", err)
	}
	if err := validateTaskFilter(filter); err != nil {
		return filter, 0, nil, err
	}
	limit := defaultTaskPageSize
	if raw := strings.TrimSpace(query.Get("limit")); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil || limit < 1 || limit > maxTaskPageSize {
			return filter, 0, nil, fmt.Errorf("limit 必须在 1 到 %d 之间", maxTaskPageSize)
		}
	}
	var cursor *taskCursor
	if raw := strings.TrimSpace(query.Get("cursor")); raw != "" {
		cursor, err = decodeTaskCursor(raw)
		if err != nil {
			return filter, 0, nil, fmt.Errorf("cursor 无效")
		}
	}
	return filter, limit, cursor, nil
}

func validateTaskFilter(filter taskFilterSnapshot) error {
	validStatuses := map[tasks.Status]bool{
		tasks.StatusQueued: true, tasks.StatusRunning: true, tasks.StatusCompleted: true,
		tasks.StatusFailed: true, tasks.StatusCanceled: true, tasks.StatusInterrupted: true,
	}
	for _, status := range filter.Statuses {
		if !validStatuses[status] {
			return fmt.Errorf("未知任务状态 %q", status)
		}
	}
	if filter.Type != "" && !validTaskType(filter.Type) {
		return fmt.Errorf("未知任务类型 %q", filter.Type)
	}
	if filter.Category != "" && filter.Category != "file" && filter.Category != "background" {
		return fmt.Errorf("未知任务分类 %q", filter.Category)
	}
	if filter.From < 0 || filter.To < 0 || (filter.From > 0 && filter.To > 0 && filter.From > filter.To) {
		return fmt.Errorf("任务时间范围无效")
	}
	return nil
}

func validTaskType(taskType tasks.Type) bool {
	switch taskType {
	case tasks.TypeTrashSize, tasks.TypeTrashClear, tasks.TypeDuplicateAnalysis, tasks.TypeDuplicateCleanup, tasks.TypeStorageAnalysis, tasks.TypeArchiveExtract, tasks.TypeMediaHLS:
		return true
	case tasks.TypeFileCopy, tasks.TypeFileMove:
		return true
	default:
		return false
	}
}

func filterTasks(all []*tasks.Task, filter taskFilterSnapshot) []*tasks.Task {
	statuses := make(map[tasks.Status]struct{}, len(filter.Statuses))
	for _, status := range filter.Statuses {
		statuses[status] = struct{}{}
	}
	text := strings.ToLower(strings.TrimSpace(filter.Text))
	user := strings.ToLower(strings.TrimSpace(filter.User))
	filtered := make([]*tasks.Task, 0, len(all))
	for _, task := range all {
		if filter.Archived != nil && (task.ArchivedAt != 0) != *filter.Archived {
			continue
		}
		if len(statuses) > 0 {
			if _, ok := statuses[task.Status]; !ok {
				continue
			}
		}
		if filter.Type != "" && task.Type != filter.Type {
			continue
		}
		if filter.Category == "file" && task.Type != tasks.TypeFileCopy && task.Type != tasks.TypeFileMove {
			continue
		}
		if filter.Category == "background" && (task.Type == tasks.TypeFileCopy || task.Type == tasks.TypeFileMove) {
			continue
		}
		if user != "" && strings.ToLower(task.OwnerName) != user && strconv.FormatUint(uint64(task.UserID), 10) != user {
			continue
		}
		if filter.From > 0 && task.CreatedAt < filter.From || filter.To > 0 && task.CreatedAt > filter.To {
			continue
		}
		if text != "" {
			haystack := strings.ToLower(strings.Join([]string{task.ID, task.Title, string(task.Type), task.Error, task.OwnerName}, "\n"))
			if !strings.Contains(haystack, text) {
				continue
			}
		}
		filtered = append(filtered, task)
	}
	return filtered
}

func summarizeTasks(all []*tasks.Task) taskListCounts {
	counts := taskListCounts{}
	for _, task := range all {
		if task.ArchivedAt != 0 {
			counts.Archived++
			continue
		}
		counts.All++
		switch task.Status {
		case tasks.StatusQueued, tasks.StatusRunning:
			counts.Active++
		case tasks.StatusFailed, tasks.StatusInterrupted:
			counts.Attention++
		case tasks.StatusCanceled:
			counts.Canceled++
		case tasks.StatusCompleted:
			counts.Completed++
		}
	}
	return counts
}

func taskOwners(all []*tasks.Task) []string {
	set := make(map[string]struct{})
	for _, task := range all {
		owner := strings.TrimSpace(task.OwnerName)
		if owner == "" {
			owner = fmt.Sprintf("用户 %d", task.UserID)
		}
		set[owner] = struct{}{}
	}
	owners := make([]string, 0, len(set))
	for owner := range set {
		owners = append(owners, owner)
	}
	sort.Strings(owners)
	return owners
}

func tasksAfterCursor(all []*tasks.Task, cursor taskCursor) []*tasks.Task {
	for index, task := range all {
		if task.CreatedAt < cursor.CreatedAt || task.CreatedAt == cursor.CreatedAt && task.ID < cursor.ID {
			return all[index:]
		}
	}
	return []*tasks.Task{}
}

func encodeTaskCursor(task *tasks.Task) string {
	encoded, _ := json.Marshal(taskCursor{CreatedAt: task.CreatedAt, ID: task.ID})
	return base64.RawURLEncoding.EncodeToString(encoded)
}

func decodeTaskCursor(raw string) (*taskCursor, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return nil, err
	}
	var cursor taskCursor
	if err := json.Unmarshal(decoded, &cursor); err != nil || cursor.CreatedAt <= 0 || cursor.ID == "" {
		return nil, fmt.Errorf("invalid cursor")
	}
	return &cursor, nil
}

func parseTaskTimestamp(raw string) (int64, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0, nil
	}
	return strconv.ParseInt(raw, 10, 64)
}

func muxTaskID(r *http.Request) string {
	return strings.TrimSpace(mux.Vars(r)["id"])
}
