package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/analysis"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestDuplicateAnalysisUsesOneCancelableGlobalWorkerSlot(t *testing.T) {
	if capacity := cap(analysisWorkerSlot); capacity != 1 {
		t.Fatalf("analysis worker capacity = %d", capacity)
	}
	analysisWorkerSlot <- struct{}{}
	defer func() { <-analysisWorkerSlot }()

	ctx, cancel := context.WithCancel(context.Background())
	d := &data{user: &users.User{Fs: afero.NewMemMapFs()}}
	runner := duplicateAnalysisRunner(d, &tasks.Task{}, duplicateAnalysisArgs{Paths: []string{"/"}})
	finished := make(chan error, 1)
	go func() {
		_, err := runner(ctx, func(tasks.Progress) error { return nil })
		finished <- err
	}()
	select {
	case err := <-finished:
		t.Fatalf("queued analysis returned before slot release: %v", err)
	case <-time.After(25 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("queued cancellation error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("queued analysis did not honor cancellation")
	}
}

func TestDuplicateAnalysisRunsOnExplicitScopesAndKeepsResultsPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner", Perm: users.Permissions{Download: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true}},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	filesystem := h.fs[owner.ID]
	if err := filesystem.MkdirAll("/scan/nested", 0o750); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"/scan/a.txt", "/scan/nested/b.txt"} {
		if err := afero.WriteFile(filesystem, name, []byte("duplicate"), 0o640); err != nil {
			t.Fatal(err)
		}
	}
	if err := afero.WriteFile(filesystem, "/outside.txt", []byte("duplicate"), 0o640); err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, duplicateAnalysisStartHandler(runtime), http.MethodPost, "/analysis/duplicates", bytes.NewBufferString(`{"paths":["/scan","/scan/nested"]}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("start status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "paths") || strings.Contains(response.Body.String(), "groups") {
		t.Fatalf("task response leaked internal args or result: %s", response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	if completed.Type != tasks.TypeDuplicateAnalysis || len(completed.Result) == 0 {
		t.Fatalf("completed analysis task = %#v", completed)
	}

	response = h.request(t, owner.ID, analysisResultHandler, http.MethodGet, "/analysis/"+created.ID, nil, map[string]string{"id": created.ID})
	if response.Code != http.StatusOK {
		t.Fatalf("result status = %d body=%s", response.Code, response.Body.String())
	}
	var report analysis.DuplicateReport
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if len(report.Scopes) != 1 || report.Scopes[0] != "/scan" || report.ScannedFiles != 2 || report.DuplicateGroups != 1 {
		t.Fatalf("analysis report = %#v", report)
	}
	if report.Groups[0].TotalFiles != 2 {
		t.Fatalf("duplicate group = %#v", report.Groups[0])
	}

	privateResponse := h.request(t, other.ID, analysisResultHandler, http.MethodGet, "/analysis/"+created.ID, nil, map[string]string{"id": created.ID})
	if privateResponse.Code != http.StatusForbidden {
		t.Fatalf("cross-user result status = %d body=%s", privateResponse.Code, privateResponse.Body.String())
	}
}

func TestDuplicateAnalysisRequiresReadPermissionAndValidScope(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "limited"},
		users.User{Username: "reader", HideDotfiles: true, Perm: users.Permissions{Download: true}},
	)
	limited := trashHTTPUserByName(t, h, "limited")
	reader := trashHTTPUserByName(t, h, "reader")
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, limited.ID, duplicateAnalysisStartHandler(runtime), http.MethodPost, "/analysis/duplicates", bytes.NewBufferString(`{"paths":["/"]}`), nil)
	if response.Code != http.StatusForbidden {
		t.Fatalf("permission status = %d body=%s", response.Code, response.Body.String())
	}

	response = h.request(t, reader.ID, duplicateAnalysisStartHandler(runtime), http.MethodPost, "/analysis/duplicates", bytes.NewBufferString(`{"paths":["/missing"]}`), nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("missing scope status = %d body=%s", response.Code, response.Body.String())
	}
	if err := h.fs[reader.ID].MkdirAll("/.hidden", 0o750); err != nil {
		t.Fatal(err)
	}
	response = h.request(t, reader.ID, duplicateAnalysisStartHandler(runtime), http.MethodPost, "/analysis/duplicates", bytes.NewBufferString(`{"paths":["/.hidden"]}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("direct hidden scope status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestStorageAnalysisSharesOneCancelableGlobalWorkerSlot(t *testing.T) {
	analysisWorkerSlot <- struct{}{}
	defer func() { <-analysisWorkerSlot }()

	ctx, cancel := context.WithCancel(context.Background())
	d := &data{user: &users.User{Fs: afero.NewMemMapFs()}}
	runner := storageAnalysisRunner(d, &tasks.Task{}, storageAnalysisArgs{Paths: []string{"/"}})
	finished := make(chan error, 1)
	go func() {
		_, err := runner(ctx, func(tasks.Progress) error { return nil })
		finished <- err
	}()
	select {
	case err := <-finished:
		t.Fatalf("queued storage analysis returned before slot release: %v", err)
	case <-time.After(25 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("queued cancellation error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("queued storage analysis did not honor cancellation")
	}
}

func TestStorageAnalysisRunsOnExplicitScopesAndKeepsResultsPrivate(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner", Perm: users.Permissions{Download: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true}},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	filesystem := h.fs[owner.ID]
	if err := filesystem.MkdirAll("/scan/nested", 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, "/scan/a.txt", []byte("12345"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, "/scan/nested/b.txt", []byte("1234567"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, "/outside.txt", []byte("outside"), 0o640); err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, owner.ID, storageAnalysisStartHandler(runtime), http.MethodPost, "/analysis/storage", bytes.NewBufferString(`{"paths":["/scan","/scan/nested"]}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("start status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "paths") || strings.Contains(response.Body.String(), "largestFiles") {
		t.Fatalf("task response leaked internal args or result: %s", response.Body.String())
	}
	var created tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	completed := waitForHTTPTask(t, h, created.ID, tasks.StatusCompleted)
	if completed.Type != tasks.TypeStorageAnalysis || len(completed.Result) == 0 {
		t.Fatalf("completed storage task = %#v", completed)
	}

	response = h.request(t, owner.ID, storageAnalysisResultHandler, http.MethodGet, "/analysis/storage/"+created.ID, nil, map[string]string{"id": created.ID})
	if response.Code != http.StatusOK {
		t.Fatalf("result status = %d body=%s", response.Code, response.Body.String())
	}
	var report analysis.StorageReport
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if len(report.Scopes) != 1 || report.Scopes[0].Path != "/scan" || report.ScannedFiles != 2 || report.ScannedBytes != 12 {
		t.Fatalf("storage report = %#v", report)
	}
	if len(report.LargestFiles) != 2 || report.LargestFiles[0].Path != "/scan/nested/b.txt" {
		t.Fatalf("largest files = %#v", report.LargestFiles)
	}

	privateResponse := h.request(t, other.ID, storageAnalysisResultHandler, http.MethodGet, "/analysis/storage/"+created.ID, nil, map[string]string{"id": created.ID})
	if privateResponse.Code != http.StatusForbidden {
		t.Fatalf("cross-user result status = %d body=%s", privateResponse.Code, privateResponse.Body.String())
	}
}

func TestAnalysisRecentSummariesStayPrivateAndDoNotExposeRawResults(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "admin", Perm: users.Permissions{Admin: true, Download: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true}},
	)
	admin := trashHTTPUserByName(t, h, "admin")
	other := trashHTTPUserByName(t, h, "other")

	createCompleted := func(owner *users.User, scope string, createdAt int64) *tasks.Task {
		t.Helper()
		args, err := json.Marshal(duplicateAnalysisArgs{Paths: []string{scope}})
		if err != nil {
			t.Fatal(err)
		}
		task, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeDuplicateAnalysis, "查找重复文件", args, "")
		if err != nil {
			t.Fatal(err)
		}
		result, err := json.Marshal(analysis.DuplicateReport{
			Scopes: []string{scope}, ScannedFiles: 12, ScannedBytes: 2048,
			DuplicateGroups: 2, ReclaimableBytes: 512,
			Groups: []analysis.DuplicateGroup{{SHA256: "internal-result-must-not-leak"}},
		})
		if err != nil {
			t.Fatal(err)
		}
		task.Status = tasks.StatusCompleted
		task.CreatedAt = createdAt
		task.FinishedAt = createdAt + 100
		task.Result = result
		if err := h.storage.Tasks.Update(task); err != nil {
			t.Fatal(err)
		}
		return task
	}

	otherTask := createCompleted(other, "/other-private", 200)
	adminTask := createCompleted(admin, "/photos", 100)
	secondAdminTask := createCompleted(admin, "/photos/second", 90)
	thirdAdminTask := createCompleted(admin, "/photos/third", 80)
	archivedAdminTask := createCompleted(admin, "/photos/archived", 70)
	archivedAdminTask.ArchivedAt = 1000
	if err := h.storage.Tasks.Update(archivedAdminTask); err != nil {
		t.Fatal(err)
	}
	response := h.request(t, admin.ID, analysisRecentHandler, http.MethodGet, "/analysis/recent?tool=duplicates&limit=2", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("recent analysis status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), otherTask.ID) || strings.Contains(response.Body.String(), "/other-private") {
		t.Fatalf("admin recent analysis leaked another user's report: %s", response.Body.String())
	}
	if strings.Contains(response.Body.String(), "internal-result-must-not-leak") || strings.Contains(response.Body.String(), "args") {
		t.Fatalf("recent analysis leaked raw task data: %s", response.Body.String())
	}
	var page analysisRecentResponse
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 || page.Items[0].ID != adminTask.ID || page.Items[1].ID != secondAdminTask.ID || page.NextCursor == "" {
		t.Fatalf("recent analysis first page = %#v", page)
	}
	if !page.Items[0].ResultReady || page.Items[0].Metrics == nil || page.Items[0].Metrics.DuplicateGroups != 2 || page.Items[0].Metrics.ReclaimableBytes != 512 {
		t.Fatalf("recent analysis metrics = %#v", page.Items[0])
	}

	response = h.request(t, admin.ID, analysisRecentHandler, http.MethodGet, "/analysis/recent?tool=duplicates&limit=2&cursor="+page.NextCursor, nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("recent analysis next page status = %d body=%s", response.Code, response.Body.String())
	}
	var nextPage analysisRecentResponse
	if err := json.Unmarshal(response.Body.Bytes(), &nextPage); err != nil {
		t.Fatal(err)
	}
	if len(nextPage.Items) != 1 || nextPage.Items[0].ID != thirdAdminTask.ID || nextPage.NextCursor != "" {
		t.Fatalf("recent analysis next page = %#v", nextPage)
	}

	response = h.request(t, admin.ID, analysisRecentHandler, http.MethodGet, "/analysis/recent?tool=invalid", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid tool status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, analysisRecentHandler, http.MethodGet, "/analysis/recent?limit=11", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid limit status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, admin.ID, analysisRecentHandler, http.MethodGet, "/analysis/recent?cursor=invalid", nil, nil)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid cursor status = %d body=%s", response.Code, response.Body.String())
	}
}
