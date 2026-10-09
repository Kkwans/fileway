package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func permissionTransferFixture(t *testing.T, action string, overwrite bool) (*trashHTTPHarness, *data, *tasks.Task, fileTransferTaskArgs) {
	t.Helper()
	h, owner, filesystem := wireOperationsHarness(t)
	writeWireOwned(t, filesystem, map[string]string{"/source.txt": "owned-new-content"})
	if overwrite {
		writeWireOwned(t, filesystem, map[string]string{"/target.txt": "owned-old-target"})
	}
	actor, err := h.storage.Users.Get(h.server.Root, owner.ID)
	if err != nil {
		t.Fatal(err)
	}
	policy, err := h.storage.Settings.Get()
	if err != nil {
		t.Fatal(err)
	}
	taskType := tasks.TypeFileCopy
	if action == "move" {
		taskType = tasks.TypeFileMove
	}
	d := &data{store: h.storage, server: h.server, settings: policy, user: actor, fileCache: noopTrashFileCache{}}
	task := &tasks.Task{ID: "owned-permission-transfer", Type: taskType, UserID: owner.ID, OwnerName: owner.Username}
	args := fileTransferTaskArgs{Items: []fileTransferItem{{From: "/source.txt", To: "/target.txt", Size: int64(len("owned-new-content")), Overwrite: overwrite}}}
	return h, d, task, args
}

func updateTransferActor(t *testing.T, h *trashHTTPHarness, id uint, change func(*users.User)) {
	t.Helper()
	actor, err := h.storage.Users.Get(h.server.Root, id)
	if err != nil {
		t.Fatal(err)
	}
	change(actor)
	if err := h.storage.Users.Update(actor, "Perm", "Rules", "Scope"); err != nil {
		t.Fatal(err)
	}
}

func assertNoTransferTemp(t *testing.T, filesystem afero.Fs) {
	t.Helper()
	entries, err := afero.ReadDir(filesystem, "/")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".nfb-transfer-") {
			t.Fatalf("owned temporary file leaked: %s", entry.Name())
		}
	}
}

func TestTransferPermissionsRejectInitialOverwriteWithoutModify(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		for _, exists := range []bool{false, true} {
			name := action + "/missing-target"
			if exists {
				name = action + "/existing-target"
			}
			t.Run(name, func(t *testing.T) {
				h, d, _, _ := permissionTransferFixture(t, action, exists)
				updateTransferActor(t, h, d.user.ID, func(actor *users.User) { actor.Perm.Modify = false })
				runtime, err := tasks.NewRuntime(h.storage.Tasks)
				if err != nil {
					t.Fatal(err)
				}
				body := `{"action":"` + action + `","items":[{"from":"/source.txt","to":"/target.txt","overwrite":true}]}`
				response := h.request(t, d.user.ID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", strings.NewReader(body), nil)
				// Drain an incorrectly accepted RED task before fixture cleanup.
				if response.Code == 202 {
					var accepted tasks.Task
					if json.Unmarshal(response.Body.Bytes(), &accepted) == nil {
						waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
					}
				}
				if response.Code != 403 {
					t.Fatalf("overwrite without Modify: status=%d %s", response.Code, response.Body.String())
				}
				all, err := h.storage.Tasks.List(d.user.ID, false)
				if err != nil || len(all) != 0 {
					t.Fatalf("rejected overwrite enqueued tasks: %#v %v", all, err)
				}
				assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content"})
				if exists {
					assertWireOwned(t, d.user.Fs, map[string]string{"/target.txt": "owned-old-target"})
				}
			})
		}
	}
}

type gatedTransfer struct {
	cancel  context.CancelFunc
	release func()
	done    chan struct{}
	result  json.RawMessage
	err     error
}

func startGatedTransfer(t *testing.T, run tasks.Runner) *gatedTransfer {
	t.Helper()
	select {
	case fileTransferGate <- struct{}{}:
	case <-time.After(time.Second):
		t.Fatal("transfer gate not available")
	}
	var once sync.Once
	ctx, cancel := context.WithCancel(context.Background())
	g := &gatedTransfer{cancel: cancel, release: func() { once.Do(func() { <-fileTransferGate }) }, done: make(chan struct{})}
	started := make(chan struct{})
	go func() {
		close(started)
		g.result, g.err = run(ctx, func(tasks.Progress) error { return nil })
		close(g.done)
	}()
	<-started
	t.Cleanup(func() {
		g.cancel()
		g.release()
		select {
		case <-g.done:
		case <-time.After(3 * time.Second):
			t.Error("owned runner did not stop during cleanup")
		}
	})
	return g
}

func TestTransferPermissionsRecheckQueuedActorAndWorkspace(t *testing.T) {
	for _, name := range []string{"copy-modify", "move-modify", "copy-create", "move-create", "move-rename", "source-rule", "destination-rule", "global-rule", "scope", "filesystem-root", "server-root"} {
		t.Run(name, func(t *testing.T) {
			action := "copy"
			if strings.HasPrefix(name, "move-") {
				action = "move"
			}
			h, d, task, args := permissionTransferFixture(t, action, true)
			originalFS := d.user.Fs
			g := startGatedTransfer(t, fileTransferRunner(d, task, args))
			var otherFS afero.Fs
			switch name {
			case "copy-modify", "move-modify":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Modify = false })
			case "copy-create", "move-create":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Create = false })
			case "move-rename":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Rename = false })
			case "source-rule":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Rules = []rules.Rule{{Path: "/source.txt", Allow: false}} })
			case "destination-rule":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Rules = []rules.Rule{{Path: "/target.txt", Allow: false}} })
			case "global-rule":
				policy, err := h.storage.Settings.Get()
				if err != nil {
					t.Fatal(err)
				}
				policy.Rules = []rules.Rule{{Path: "/target.txt", Allow: false}}
				if err := h.storage.Settings.Save(policy); err != nil {
					t.Fatal(err)
				}
			case "scope":
				updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Scope = filepath.Join(t.TempDir(), "changed-scope") })
			case "server-root":
				h.server.Root = filepath.Join(t.TempDir(), "changed-server-root")
			case "filesystem-root":
				otherFS = afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
				writeWireOwned(t, otherFS, map[string]string{"/source.txt": "other-source", "/target.txt": "other-target"})
				h.fs[d.user.ID] = otherFS
			}
			g.release()
			select {
			case <-g.done:
			case <-time.After(3 * time.Second):
				t.Fatal("queued runner did not finish")
			}
			if g.err == nil {
				t.Fatalf("queued %s executed with stale authorization: %s", name, g.result)
			}
			assertWireOwned(t, originalFS, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
			if otherFS != nil {
				assertWireOwned(t, otherFS, map[string]string{"/source.txt": "other-source", "/target.txt": "other-target"})
			}
			assertNoTransferTemp(t, originalFS)
		})
	}
}

func TestTransferPermissionsWaitingGateCancellationFinishesWithoutFilesystemWrites(t *testing.T) {
	_, d, task, args := permissionTransferFixture(t, "copy", true)
	g := startGatedTransfer(t, fileTransferRunner(d, task, args))
	g.cancel()
	select {
	case <-g.done:
	case <-time.After(time.Second):
		t.Fatal("cancelled runner is still blocked behind another transfer")
	}
	if !errors.Is(g.err, context.Canceled) {
		t.Fatalf("waiting cancellation err=%v", g.err)
	}
	if len(fileTransferGate) != 1 {
		t.Fatal("waiting cancellation released another task's gate")
	}
	assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
	assertNoTransferTemp(t, d.user.Fs)
}

func TestTransferPermissionsRecheckAfterCopyBeforeOverwrite(t *testing.T) {
	for _, name := range []string{"copy-modify", "copy-create", "move-rename", "destination-rule", "scope"} {
		t.Run(name, func(t *testing.T) {
			action := "copy"
			if name == "move-rename" {
				action = "move"
			}
			h, d, task, args := permissionTransferFixture(t, action, true)
			revoked := false
			_, err := fileTransferRunner(d, task, args)(context.Background(), func(progress tasks.Progress) error {
				if progress.ProcessedBytes > 0 && !revoked {
					revoked = true
					updateTransferActor(t, h, d.user.ID, func(a *users.User) {
						switch name {
						case "copy-modify":
							a.Perm.Modify = false
						case "copy-create":
							a.Perm.Create = false
						case "move-rename":
							a.Perm.Rename = false
						case "destination-rule":
							a.Rules = []rules.Rule{{Path: "/target.txt", Allow: false}}
						case "scope":
							a.Scope = filepath.Join(t.TempDir(), "changed-scope")
						}
					})
				}
				return nil
			})
			if !revoked || err == nil {
				t.Fatalf("real copied-byte revocation was not enforced: revoked=%v err=%v", revoked, err)
			}
			assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
			assertNoTransferTemp(t, d.user.Fs)
		})
	}
}

type transferPublishHookFS struct {
	afero.Fs
	published func()
}

func (fs *transferPublishHookFS) Rename(from, to string) error {
	err := fs.Fs.Rename(from, to)
	if err == nil && strings.HasPrefix(path.Base(from), ".nfb-transfer-") && to == "/target.txt" {
		fs.published()
	}
	return err
}

func TestTransferPermissionsRevokedAfterPublishKeepsSourceAndReportsFailure(t *testing.T) {
	h, d, task, args := permissionTransferFixture(t, "move", true)
	called := false
	wrapped := &transferPublishHookFS{Fs: d.user.Fs, published: func() {
		called = true
		updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Rename = false })
	}}
	h.fs[d.user.ID] = wrapped
	d.user.Fs = wrapped
	encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
	if !called || err == nil {
		t.Fatalf("published revocation: called=%v err=%v result=%s", called, err, encoded)
	}
	var result fileTransferResult
	if json.Unmarshal(encoded, &result) != nil || len(result.Failed) != 1 || len(result.Completed) != 0 {
		t.Fatalf("partial failure result=%s", encoded)
	}
	assertWireOwned(t, wrapped, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-new-content"})
	assertNoTransferTemp(t, wrapped)
}

func TestTransferPermissionsAuthorizedOverwriteStillCompletesAndCheckpoints(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			_, d, task, args := permissionTransferFixture(t, action, true)
			var checkpoint json.RawMessage
			encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(p tasks.Progress) error {
				if len(p.Checkpoint) > 0 {
					checkpoint = append(json.RawMessage(nil), p.Checkpoint...)
				}
				return nil
			})
			if err != nil {
				t.Fatalf("authorized %s: %v %s", action, err, encoded)
			}
			assertWireOwned(t, d.user.Fs, map[string]string{"/target.txt": "owned-new-content"})
			if action == "copy" {
				assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content"})
			} else if exists, _ := afero.Exists(d.user.Fs, "/source.txt"); exists {
				t.Fatal("authorized move left source")
			}
			var saved fileTransferCheckpoint
			if json.Unmarshal(checkpoint, &saved) != nil || !completedCheckpoint(d.user.Fs, saved.Completed, args.Items[0]) {
				t.Fatalf("authorized checkpoint=%s", checkpoint)
			}
			assertNoTransferTemp(t, d.user.Fs)
		})
	}
}

func TestTransferPermissionsFirstCheckpointSurvivesCancellationOrLaterRevocation(t *testing.T) {
	for _, mode := range []string{"cancel", "revoke-create"} {
		t.Run(mode, func(t *testing.T) {
			h, d, task, args := permissionTransferFixture(t, "copy", true)
			writeWireOwned(t, d.user.Fs, map[string]string{"/second-source.txt": "owned-second-source", "/second-target.txt": "owned-second-target"})
			args.Items = append(args.Items, fileTransferItem{From: "/second-source.txt", To: "/second-target.txt", Size: int64(len("owned-second-source")), Overwrite: true})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			var checkpoint json.RawMessage
			changed := false
			encoded, err := fileTransferRunner(d, task, args)(ctx, func(progress tasks.Progress) error {
				if progress.ProcessedItems == 1 && len(progress.Checkpoint) > 0 && !changed {
					changed = true
					checkpoint = append(json.RawMessage(nil), progress.Checkpoint...)
					if mode == "cancel" {
						cancel()
					} else {
						updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Create = false })
					}
				}
				return nil
			})
			if !changed || err == nil || (mode == "cancel" && !errors.Is(err, context.Canceled)) {
				t.Fatalf("second-item %s: changed=%v err=%v result=%s", mode, changed, err, encoded)
			}
			var result fileTransferResult
			if json.Unmarshal(encoded, &result) != nil || len(result.Completed) != 1 || result.Completed[0].From != "/source.txt" {
				t.Fatalf("partial completed result=%s", encoded)
			}
			var saved fileTransferCheckpoint
			if json.Unmarshal(checkpoint, &saved) != nil || len(saved.Completed) != 1 || !completedCheckpoint(d.user.Fs, saved.Completed, args.Items[0]) {
				t.Fatalf("first durable checkpoint=%s", checkpoint)
			}
			assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-new-content", "/second-source.txt": "owned-second-source", "/second-target.txt": "owned-second-target"})
			assertNoTransferTemp(t, d.user.Fs)
		})
	}
}

type transferRemoveHookFS struct {
	afero.Fs
	removed func()
}

func (fs *transferRemoveHookFS) RemoveAll(name string) error {
	err := fs.Fs.RemoveAll(name)
	if err == nil && name == "/target.txt" {
		fs.removed()
	}
	return err
}

func TestTransferPermissionsAuthorizedPublishDoesNotStrandRemovedTarget(t *testing.T) {
	h, d, task, args := permissionTransferFixture(t, "copy", true)
	called := false
	wrapped := &transferRemoveHookFS{Fs: d.user.Fs, removed: func() {
		called = true
		updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Modify = false })
	}}
	h.fs[d.user.ID], d.user.Fs = wrapped, wrapped
	encoded, err := fileTransferRunner(d, task, args)(context.Background(), func(tasks.Progress) error { return nil })
	if !called || err != nil {
		t.Fatalf("already-authorized publication must not strand its removed target: removed=%v err=%v result=%s", called, err, encoded)
	}
	assertWireOwned(t, wrapped, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-new-content"})
	assertNoTransferTemp(t, wrapped)
}

func permissionRetryOriginal(t *testing.T, h *trashHTTPHarness, d *data, task *tasks.Task, args fileTransferTaskArgs) (*tasks.Runtime, *tasks.Task) {
	t.Helper()
	encoded, err := json.Marshal(args)
	if err != nil {
		t.Fatal(err)
	}
	original, err := h.storage.Tasks.New(d.user.ID, d.user.Username, task.Type, "owned retry", encoded, "")
	if err != nil {
		t.Fatal(err)
	}
	original.Status = tasks.StatusFailed
	if err := h.storage.Tasks.Update(original); err != nil {
		t.Fatal(err)
	}
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	return runtime, original
}

func permissionRetryAdmin(t *testing.T, h *trashHTTPHarness) uint {
	t.Helper()
	admin := &users.User{Username: "owned-admin", Password: "fixture-only-password", Scope: filepath.Join(t.TempDir(), "admin"), Perm: users.Permissions{Admin: true, Create: true, Modify: true, Rename: true}}
	if err := h.storage.Users.Save(admin); err != nil {
		t.Fatal(err)
	}
	return admin.ID
}

func assertPermissionRetryOriginal(t *testing.T, h *trashHTTPHarness, original *tasks.Task) {
	t.Helper()
	all, err := h.storage.Tasks.List(original.UserID, true)
	if err != nil || len(all) != 1 {
		t.Fatalf("denied retry created a new task: count=%d err=%v", len(all), err)
	}
	saved, err := h.storage.Tasks.Get(original.UserID, original.ID, false)
	if err != nil || saved.Status != original.Status || !bytes.Equal(saved.Args, original.Args) || !bytes.Equal(saved.Result, original.Result) {
		t.Fatalf("denied retry mutated original: saved=%#v err=%v", saved, err)
	}
}

func TestTransferPermissionsRetryRejectsCurrentOwnerBeforeCreatingTask(t *testing.T) {
	for _, kind := range []string{"copy-modify", "move-modify", "copy-create", "move-create", "move-rename", "source-rule"} {
		for _, adminRequester := range []bool{false, true} {
			name := kind + "/owner-requester"
			if adminRequester {
				name = kind + "/admin-requester"
			}
			t.Run(name, func(t *testing.T) {
				action := "copy"
				if strings.HasPrefix(kind, "move-") {
					action = "move"
				}
				h, d, task, args := permissionTransferFixture(t, action, true)
				runtime, original := permissionRetryOriginal(t, h, d, task, args)
				key, _, err := fileTransferIdentity(d.user.Fs, args.Items[0])
				if err != nil {
					t.Fatal(err)
				}
				original.Result, err = json.Marshal(fileTransferCheckpoint{Completed: map[string]bool{key: true}})
				if err != nil {
					t.Fatal(err)
				}
				if err := h.storage.Tasks.Update(original); err != nil {
					t.Fatal(err)
				}
				requester := d.user.ID
				if adminRequester {
					requester = permissionRetryAdmin(t, h)
				}
				updateTransferActor(t, h, d.user.ID, func(a *users.User) {
					switch kind {
					case "copy-modify", "move-modify":
						a.Perm.Modify = false
					case "copy-create", "move-create":
						a.Perm.Create = false
					case "move-rename":
						a.Perm.Rename = false
					case "source-rule":
						a.Rules = []rules.Rule{{Path: "/source.txt", Allow: false}}
					}
				})
				response := h.request(t, requester, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
				if response.Code == 202 {
					var accepted tasks.Task
					if json.Unmarshal(response.Body.Bytes(), &accepted) == nil {
						waitForHTTPTask(t, h, accepted.ID, tasks.StatusFailed)
					}
				}
				if response.Code != 403 {
					t.Fatalf("retry must reject owner authorization before Store.New: %d %s", response.Code, response.Body.String())
				}
				assertPermissionRetryOriginal(t, h, original)
				assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
			})
		}
	}
}

func TestTransferPermissionsRetryKeepsCodecErrorsSeparateAndOriginalUntouched(t *testing.T) {
	for _, brokenResult := range []bool{false, true} {
		name := "args"
		if brokenResult {
			name = "result"
		}
		t.Run(name, func(t *testing.T) {
			h, d, task, args := permissionTransferFixture(t, "copy", true)
			runtime, original := permissionRetryOriginal(t, h, d, task, args)
			if brokenResult {
				original.Result = json.RawMessage(`{"completed":[{"from":"/lost�","to":"/target.txt"}]}`)
			} else {
				original.Args = json.RawMessage(`{"items":`)
			}
			if err := h.storage.Tasks.Update(original); err != nil {
				t.Fatal(err)
			}
			admin := permissionRetryAdmin(t, h)
			response := h.request(t, admin, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
			if response.Code != 409 && response.Code != 400 {
				t.Fatalf("codec failure is not permission denial: %d %s", response.Code, response.Body.String())
			}
			assertPermissionRetryOriginal(t, h, original)
			assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
		})
	}
}

func TestTransferPermissionsAuthorizedOwnerRetryStillCompletes(t *testing.T) {
	for _, action := range []string{"copy", "move"} {
		t.Run(action, func(t *testing.T) {
			h, d, task, args := permissionTransferFixture(t, action, true)
			runtime, original := permissionRetryOriginal(t, h, d, task, args)
			admin := permissionRetryAdmin(t, h)
			response := h.request(t, admin, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
			if response.Code != 202 {
				t.Fatalf("authorized owner retry: %d %s", response.Code, response.Body.String())
			}
			var accepted tasks.Task
			if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
				t.Fatal(err)
			}
			done := waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
			if done.UserID != original.UserID || done.RetryOf != original.ID {
				t.Fatalf("admin requester replaced owner identity: %#v", done)
			}
			assertWireOwned(t, d.user.Fs, map[string]string{"/target.txt": "owned-new-content"})
			if action == "copy" {
				assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content"})
			} else if exists, _ := afero.Exists(d.user.Fs, "/source.txt"); exists {
				t.Fatal("authorized retry move left source")
			}
		})
	}
}

type transferStatHookFS struct {
	afero.Fs
	found func()
}

func (fs *transferStatHookFS) Stat(name string) (os.FileInfo, error) {
	info, err := fs.Fs.Stat(name)
	if err == nil && name == "/source.txt" {
		fs.found()
	}
	return info, err
}

func TestTransferPermissionsInitialRequestRechecksBeforeCreatingTask(t *testing.T) {
	h, d, _, _ := permissionTransferFixture(t, "copy", true)
	var once sync.Once
	wrapped := &transferStatHookFS{Fs: d.user.Fs, found: func() {
		once.Do(func() { updateTransferActor(t, h, d.user.ID, func(a *users.User) { a.Perm.Modify = false }) })
	}}
	h.fs[d.user.ID] = wrapped
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, d.user.ID, fileTransferTaskHandler(runtime), http.MethodPost, "/resources/transfer", strings.NewReader(`{"action":"copy","items":[{"from":"/source.txt","to":"/target.txt","overwrite":true}]}`), nil)
	if response.Code == 202 {
		var accepted tasks.Task
		if json.Unmarshal(response.Body.Bytes(), &accepted) == nil {
			waitForHTTPTask(t, h, accepted.ID, tasks.StatusFailed)
		}
	}
	if response.Code != 403 {
		t.Fatalf("authorization changed during initial validation: %d %s", response.Code, response.Body.String())
	}
	all, err := h.storage.Tasks.List(d.user.ID, false)
	if err != nil || len(all) != 0 {
		t.Fatalf("initial request created tasks after revocation: %#v %v", all, err)
	}
	assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
}

func TestTransferPermissionsUnknownOriginalStateIsNotResent(t *testing.T) {
	h, d, task, args := permissionTransferFixture(t, "copy", true)
	runtime, original := permissionRetryOriginal(t, h, d, task, args)
	original.Status = tasks.Status("unknown")
	if err := h.storage.Tasks.Update(original); err != nil {
		t.Fatal(err)
	}
	admin := permissionRetryAdmin(t, h)
	response := h.request(t, admin, taskRetryHandler(runtime), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
	if response.Code != 409 {
		t.Fatalf("unknown original state was resent: %d %s", response.Code, response.Body.String())
	}
	assertPermissionRetryOriginal(t, h, original)
	assertWireOwned(t, d.user.Fs, map[string]string{"/source.txt": "owned-new-content", "/target.txt": "owned-old-target"})
}
