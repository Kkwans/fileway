package fbhttp

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestTaskHTTPVisibilityAndCancellation(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true, Delete: true}},
		users.User{Username: "member", Perm: users.Permissions{Delete: true}},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	memberTask, err := h.storage.Tasks.New(member.ID, member.Username, tasks.TypeTrashClear, "member task", json.RawMessage(`{"allUsers":false}`), "")
	if err != nil {
		t.Fatal(err)
	}
	adminTask, err := h.storage.Tasks.New(admin.ID, admin.Username, tasks.TypeTrashClear, "admin task", json.RawMessage(`{"allUsers":true}`), "")
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, member.ID, taskListHandler, http.MethodGet, "/tasks", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("member list status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "allUsers") {
		t.Fatalf("task replay args leaked: %s", response.Body.String())
	}
	var listed taskListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	visible := listed.Items
	if len(visible) != 1 || visible[0].ID != memberTask.ID {
		t.Fatalf("member tasks = %#v", visible)
	}

	response = h.request(t, member.ID, taskGetHandler, http.MethodGet, "/tasks/"+adminTask.ID, nil, map[string]string{"id": adminTask.ID})
	if response.Code != http.StatusForbidden {
		t.Fatalf("cross-user task status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, member.ID, taskCancelHandler(runtime), http.MethodPost, "/tasks/"+adminTask.ID+"/cancel", nil, map[string]string{"id": adminTask.ID})
	if response.Code != http.StatusForbidden {
		t.Fatalf("cross-user cancel status = %d body=%s", response.Code, response.Body.String())
	}

	response = h.request(t, admin.ID, taskListHandler, http.MethodGet, "/tasks", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("admin list status = %d body=%s", response.Code, response.Body.String())
	}
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	visible = listed.Items
	if len(visible) != 2 {
		t.Fatalf("admin tasks = %#v", visible)
	}
	response = h.request(t, admin.ID, taskCancelHandler(runtime), http.MethodPost, "/tasks/"+memberTask.ID+"/cancel", nil, map[string]string{"id": memberTask.ID})
	if response.Code != http.StatusAccepted {
		t.Fatalf("admin cancel status = %d body=%s", response.Code, response.Body.String())
	}
	canceled, err := h.storage.Tasks.Get(admin.ID, memberTask.ID, true)
	if err != nil {
		t.Fatal(err)
	}
	if canceled.Status != tasks.StatusCanceled {
		t.Fatalf("canceled task = %#v", canceled)
	}
}

func TestTaskFiltersPaginationArchiveAndBatchContract(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true, Delete: true}},
		users.User{Username: "member", Perm: users.Permissions{Delete: true}},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	create := func(title string, status tasks.Status) *tasks.Task {
		t.Helper()
		task, err := h.storage.Tasks.New(member.ID, member.Username, tasks.TypeTrashClear, title, json.RawMessage(`{"allUsers":false}`), "")
		if err != nil {
			t.Fatal(err)
		}
		task.Status = status
		if status != tasks.StatusQueued && status != tasks.StatusRunning {
			task.FinishedAt = time.Now().UnixMilli()
		}
		if err := h.storage.Tasks.Update(task); err != nil {
			t.Fatal(err)
		}
		return task
	}
	failed := create("failed task", tasks.StatusFailed)
	canceled := create("canceled task", tasks.StatusCanceled)
	completed := create("completed task", tasks.StatusCompleted)

	response := h.request(t, member.ID, taskListHandler, http.MethodGet, "/tasks?status=failed,interrupted", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("attention list status = %d body=%s", response.Code, response.Body.String())
	}
	var listed taskListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 1 || len(listed.Items) != 1 || listed.Items[0].ID != failed.ID {
		t.Fatalf("attention list = %#v", listed)
	}
	if listed.Counts.All != 3 || listed.Counts.Attention != 1 || listed.Counts.Canceled != 1 || listed.Counts.Completed != 1 {
		t.Fatalf("task counts = %#v", listed.Counts)
	}

	response = h.request(t, member.ID, taskListHandler, http.MethodGet, "/tasks?limit=1", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("first page status = %d body=%s", response.Code, response.Body.String())
	}
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 3 || len(listed.Items) != 1 || listed.NextCursor == "" {
		t.Fatalf("first page = %#v", listed)
	}
	firstID := listed.Items[0].ID
	response = h.request(t, member.ID, taskListHandler, http.MethodGet, "/tasks?limit=1&cursor="+listed.NextCursor, nil, nil)
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if len(listed.Items) != 1 || listed.Items[0].ID == firstID {
		t.Fatalf("second page = %#v", listed)
	}

	response = h.request(t, member.ID, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+canceled.ID+"/retry", nil, map[string]string{"id": canceled.ID})
	if response.Code != http.StatusConflict {
		t.Fatalf("canceled retry status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, member.ID, taskArchiveHandler(true), http.MethodPost, "/tasks/"+canceled.ID+"/archive", nil, map[string]string{"id": canceled.ID})
	if response.Code != http.StatusOK {
		t.Fatalf("archive status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, member.ID, taskListHandler, http.MethodGet, "/tasks?archived=true", nil, nil)
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 1 || listed.Items[0].ID != canceled.ID || listed.Counts.Archived != 1 {
		t.Fatalf("archived list = %#v", listed)
	}

	mismatch := `{"action":"archive","filters":{"statuses":["failed"],"archived":false},"expectedCount":2}`
	response = h.request(t, member.ID, taskBatchHandler(runtime), http.MethodPost, "/tasks/batch", strings.NewReader(mismatch), nil)
	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), `"actualCount":1`) {
		t.Fatalf("batch mismatch status = %d body=%s", response.Code, response.Body.String())
	}
	zeroMismatch := `{"action":"archive","filters":{"statuses":["failed"],"archived":false,"text":"missing"},"expectedCount":1}`
	response = h.request(t, member.ID, taskBatchHandler(runtime), http.MethodPost, "/tasks/batch", strings.NewReader(zeroMismatch), nil)
	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), `"actualCount":0`) {
		t.Fatalf("zero batch mismatch status = %d body=%s", response.Code, response.Body.String())
	}
	archive := `{"action":"archive","filters":{"statuses":["failed"],"archived":false},"expectedCount":1}`
	response = h.request(t, member.ID, taskBatchHandler(runtime), http.MethodPost, "/tasks/batch", strings.NewReader(archive), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("batch archive status = %d body=%s", response.Code, response.Body.String())
	}
	stored, err := h.storage.Tasks.Get(member.ID, failed.ID, false)
	if err != nil || stored.ArchivedAt == 0 {
		t.Fatalf("batch archived task = %#v err=%v", stored, err)
	}

	response = h.request(t, admin.ID, taskListHandler, http.MethodGet, "/tasks?user=member&text=completed", nil, nil)
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 1 || listed.Items[0].ID != completed.ID || len(listed.Owners) != 1 || listed.Owners[0] != member.Username {
		t.Fatalf("admin filtered list = %#v", listed)
	}
}

func TestTaskListIncludesCompleteCategoryCounts(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "owner",
		Perm:     users.Permissions{Modify: true},
	})
	owner := firstTrashHTTPUser(h)
	if _, err := h.storage.Tasks.New(
		owner.ID,
		owner.Username,
		tasks.TypeTrashClear,
		"后台任务",
		json.RawMessage(`{}`),
		"",
	); err != nil {
		t.Fatal(err)
	}
	fileTask, err := h.storage.Tasks.New(
		owner.ID,
		owner.Username,
		tasks.TypeFileCopy,
		"文件任务",
		json.RawMessage(`{}`),
		"",
	)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, taskListHandler, http.MethodGet, "/tasks?category=file", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("category list status = %d body=%s", response.Code, response.Body.String())
	}
	var listed taskListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if listed.Total != 1 || len(listed.Items) != 1 || listed.Items[0].ID != fileTask.ID {
		t.Fatalf("file category list = %#v", listed)
	}
	if listed.Counts.All != 2 || listed.Counts.Active != 2 {
		t.Fatalf("global counts = %#v", listed.Counts)
	}
	if listed.CategoryCounts["file"].All != 1 || listed.CategoryCounts["file"].Active != 1 {
		t.Fatalf("file category counts = %#v", listed.CategoryCounts["file"])
	}
	if listed.CategoryCounts["background"].All != 1 || listed.CategoryCounts["background"].Active != 1 {
		t.Fatalf("background category counts = %#v", listed.CategoryCounts["background"])
	}
}

func TestTaskDeleteRecordsArchivesOnlyTerminalCategoryTasks(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner"})
	owner := firstTrashHTTPUser(h)
	completed, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeFileCopy, "完成复制", json.RawMessage(`{}`), "")
	if err != nil {
		t.Fatal(err)
	}
	completed.Status = tasks.StatusCompleted
	completed.FinishedAt = time.Now().UnixMilli()
	if err := h.storage.Tasks.Update(completed); err != nil {
		t.Fatal(err)
	}
	active, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeFileMove, "正在移动", json.RawMessage(`{}`), "")
	if err != nil {
		t.Fatal(err)
	}
	background, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeTrashClear, "后台任务", json.RawMessage(`{}`), "")
	if err != nil {
		t.Fatal(err)
	}
	background.Status = tasks.StatusCompleted
	background.FinishedAt = time.Now().UnixMilli()
	if err := h.storage.Tasks.Update(background); err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, taskDeleteRecordsHandler, http.MethodDelete, "/tasks?category=file", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("clear file records status = %d body=%s", response.Code, response.Body.String())
	}
	var deleted taskDeleteRecordsResponse
	if err := json.Unmarshal(response.Body.Bytes(), &deleted); err != nil {
		t.Fatal(err)
	}
	if deleted.Deleted != 1 {
		t.Fatalf("deleted = %#v", deleted)
	}
	stored, err := h.storage.Tasks.Get(owner.ID, completed.ID, false)
	if err != nil || stored.ArchivedAt == 0 {
		t.Fatalf("completed task = %#v err=%v", stored, err)
	}
	activeStored, err := h.storage.Tasks.Get(owner.ID, active.ID, false)
	if err != nil || activeStored.ArchivedAt != 0 {
		t.Fatalf("active task = %#v err=%v", activeStored, err)
	}
}

func TestTrashClearRunsAsTrackedTaskAndScopesNormalUser(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "first", Perm: users.Permissions{Delete: true, Modify: true, Download: true}},
		users.User{Username: "second", Perm: users.Permissions{Delete: true, Modify: true, Download: true}},
	)
	first := trashHTTPUserByName(t, h, "first")
	second := trashHTTPUserByName(t, h, "second")
	moveTrashFixture(t, h, first, "/first.txt")
	moveTrashFixture(t, h, second, "/second.txt")
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, first.ID, trashClearHandler(runtime), http.MethodDelete, "/trash", nil, nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("clear status = %d body=%s", response.Code, response.Body.String())
	}
	if contentType := response.Header().Get("Content-Type"); !strings.Contains(contentType, "application/json") {
		t.Fatalf("clear content type = %q", contentType)
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	if completed.TotalItems != 1 || completed.ProcessedItems != 1 {
		t.Fatalf("clear progress = %#v", completed)
	}
	firstItems, err := h.storage.Trash.List(first.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	secondItems, err := h.storage.Trash.List(second.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(firstItems) != 0 || len(secondItems) != 1 {
		t.Fatalf("scoped clear left first=%d second=%d", len(firstItems), len(secondItems))
	}
}

func TestInterruptedTrashClearRequiresExplicitRetry(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		Username: "member", Perm: users.Permissions{Delete: true, Modify: true, Download: true},
	})
	member := trashHTTPUserByName(t, h, "member")
	moveTrashFixture(t, h, member, "/retry.txt")
	original, err := h.storage.Tasks.New(member.ID, member.Username, tasks.TypeTrashClear, "清空回收站", json.RawMessage(`{"allUsers":false}`), "")
	if err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	interrupted, err := h.storage.Tasks.Get(member.ID, original.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if interrupted.Status != tasks.StatusInterrupted {
		t.Fatalf("startup task = %#v", interrupted)
	}

	response := h.request(t, member.ID, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
	if response.Code != http.StatusAccepted {
		t.Fatalf("retry status = %d body=%s", response.Code, response.Body.String())
	}
	var retry tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &retry); err != nil {
		t.Fatal(err)
	}
	if retry.RetryOf != original.ID || retry.ID == original.ID {
		t.Fatalf("retry relation = %#v", retry)
	}
	waitForHTTPTask(t, h, retry.ID, tasks.StatusCompleted)
	remaining, err := h.storage.Trash.List(member.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(remaining) != 0 {
		t.Fatalf("retry did not clear trash: %#v", remaining)
	}
}

func TestAdminTrashClearProcessesEveryOwnersFilesystem(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true, Delete: true}},
		users.User{Username: "member", Perm: users.Permissions{Delete: true, Modify: true, Download: true}},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	member := trashHTTPUserByName(t, h, "member")
	moveTrashFixture(t, h, member, "/member-owned.txt")
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, admin.ID, trashClearHandler(runtime), http.MethodDelete, "/trash", nil, nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("admin clear status = %d body=%s", response.Code, response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	remaining, err := h.storage.Trash.List(admin.ID, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(remaining) != 0 {
		t.Fatalf("admin clear left items: %#v", remaining)
	}
	if exists, _ := afero.Exists(h.fs[member.ID], "/.nas-file-browser-trash"); !exists {
		// The per-volume root is intentionally retained for future moves; only
		// the tracked item directory must be gone.
		t.Fatal("admin clear removed the recycle-bin root")
	}
}

func moveTrashFixture(t *testing.T, h *trashHTTPHarness, owner *users.User, path string) {
	t.Helper()
	if err := afero.WriteFile(h.fs[owner.ID], path, []byte("fixture"), 0o640); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, resourceDeleteHandler(noopTrashFileCache{}), http.MethodDelete, path+"?mode=trash", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("move fixture status = %d body=%s", response.Code, response.Body.String())
	}
}

func waitForHTTPTask(t *testing.T, h *trashHTTPHarness, id string, status tasks.Status) *tasks.Task {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		task, err := h.storage.Tasks.Get(0, id, true)
		if err != nil {
			t.Fatal(err)
		}
		if task.Status == status {
			return task
		}
		time.Sleep(time.Millisecond)
	}
	task, _ := h.storage.Tasks.Get(0, id, true)
	t.Fatalf("task did not reach %q: %#v", status, task)
	return nil
}

func trashHTTPUserByName(t *testing.T, h *trashHTTPHarness, username string) *users.User {
	t.Helper()
	for _, user := range h.users {
		if user.Username == username {
			return user
		}
	}
	t.Fatalf("user %q not found", username)
	return nil
}
