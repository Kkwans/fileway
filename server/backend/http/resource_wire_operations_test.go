package fbhttp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/asdine/storm/v3"
	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/storage/bolt"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func wireOperationsHarness(t *testing.T) (*trashHTTPHarness, *users.User, afero.Fs) {
	t.Helper()
	scope := filepath.Join(t.TempDir(), "owner")
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Scope: scope, Perm: users.Permissions{Create: true, Modify: true, Rename: true, Delete: true, Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.NewOsFs().MkdirAll(scope, 0o700); err != nil {
		t.Fatal(err)
	}
	filesystem := afero.NewBasePathFs(afero.NewOsFs(), scope)
	h.fs[owner.ID] = filesystem
	return h, owner, filesystem
}

func opaqueWireOperationsHarness(t *testing.T) (*trashHTTPHarness, *users.User, afero.Fs) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("Owned non-UTF8 filenames require a byte-preserving OS filesystem")
	}
	return wireOperationsHarness(t)
}

func writeWireOwned(t *testing.T, filesystem afero.Fs, names map[string]string) {
	t.Helper()
	for name, content := range names {
		if err := afero.WriteFile(filesystem, name, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
}

func assertWireOwned(t *testing.T, filesystem afero.Fs, names map[string]string) {
	t.Helper()
	for name, expected := range names {
		content, err := afero.ReadFile(filesystem, name)
		if err != nil || string(content) != expected {
			t.Fatalf("path bytes %x: content=%q err=%v, want=%q", []byte(name), content, err, expected)
		}
	}
}

func TestWireOperationsTransferHTTPPreservesOpaqueArgsAndResults(t *testing.T) {
	h, owner, filesystem := opaqueWireOperationsHarness(t)
	source := "/\xd6\xd0\xce\xc4.txt"
	siblings := map[string]string{"/中文.txt": "unicode-sibling", "/%D6%D0%CE%C4.txt": "literal-percent-sibling"}
	writeWireOwned(t, filesystem, siblings)
	writeWireOwned(t, filesystem, map[string]string{source: "owned-opaque"})
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"copy", "move"} {
		target := "/" + action + "-\xd6\xd0\xce\xc4.txt"
		targetSiblings := map[string]string{files.DisplayPath(target): "unicode-target", files.EncodeWirePath(target): "literal-percent-target"}
		writeWireOwned(t, filesystem, targetSiblings)
		body, _ := json.Marshal(map[string]any{"action": action, "items": []map[string]string{{
			"from": "/files" + files.EncodeWirePath(source), "to": "/files" + files.EncodeWirePath(target),
			"fromWirePath": files.EncodeWirePath(source), "toWirePath": files.EncodeWirePath(target),
		}}})
		response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
		if response.Code != 202 {
			t.Fatalf("%s: %d %s", action, response.Code, response.Body.String())
		}
		var accepted tasks.Task
		if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
			t.Fatal(err)
		}
		done := waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
		var args fileTransferTaskArgs
		if err := json.Unmarshal(done.Args, &args); err != nil || len(args.Items) != 1 || args.Items[0].From != source || args.Items[0].To != target {
			t.Fatalf("durable %s args=%s decoded=%#v err=%v", action, done.Args, args, err)
		}
		var result fileTransferResult
		if err := json.Unmarshal(done.Result, &result); err != nil || len(result.Completed) != 1 || result.Completed[0].From != source || result.Completed[0].To != target {
			t.Fatalf("durable %s result=%s decoded=%#v err=%v", action, done.Result, result, err)
		}
		assertWireOwned(t, filesystem, map[string]string{target: "owned-opaque"})
		assertWireOwned(t, filesystem, siblings)
		assertWireOwned(t, filesystem, targetSiblings)
	}
	if exists, _ := afero.Exists(filesystem, source); exists {
		t.Fatal("move left its exact opaque source")
	}
}

func TestWireOperationsDeletionHTTPKeepsUndoAndExactIdentity(t *testing.T) {
	h, owner, filesystem := opaqueWireOperationsHarness(t)
	source := "/\xd6\xd0\xce\xc4.txt"
	siblings := map[string]string{"/中文.txt": "unicode-sibling", "/%D6%D0%CE%C4.txt": "literal-percent-sibling"}
	writeWireOwned(t, filesystem, siblings)
	writeWireOwned(t, filesystem, map[string]string{source: "owned-opaque"})
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"kind": "resources", "paths": []string{files.DisplayPath(source)}, "wirePaths": []string{files.EncodeWirePath(source)}})
	handler := pendingDeletionHandler(runner)
	withCache := func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		d.fileCache = noopTrashFileCache{}
		return handler(w, r, d)
	}
	response := h.request(t, owner.ID, withCache, http.MethodPost, "/deletions", bytes.NewReader(body), nil)
	if response.Code != 202 {
		t.Fatalf("deletion: %d %s", response.Code, response.Body.String())
	}
	var accepted tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	if accepted.UndoUntil <= time.Now().UnixMilli() {
		t.Fatal("deletion lost its undo window")
	}
	assertWireOwned(t, filesystem, map[string]string{source: "owned-opaque"})
	// Wait for the real undo deadline, then use the existing bounded task waiter.
	time.Sleep(time.Until(time.UnixMilli(accepted.UndoUntil)))
	done := waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
	var args pendingDeletionArgs
	if err := json.Unmarshal(done.Args, &args); err != nil || len(args.Paths) != 1 || args.Paths[0] != source {
		t.Fatalf("durable deletion args=%s decoded=%#v err=%v", done.Args, args, err)
	}
	var result []map[string]any
	if err := json.Unmarshal(done.Result, &result); err != nil || len(result) != 1 || result[0]["targetWirePath"] != files.EncodeWirePath(source) {
		t.Fatalf("deletion result=%s err=%v", done.Result, err)
	}
	if exists, _ := afero.Exists(filesystem, source); exists {
		t.Fatal("exact deletion target still exists")
	}
	assertWireOwned(t, filesystem, siblings)
}

func TestWireOperationsPatchOpaqueParentAndLiteralPercent(t *testing.T) {
	h, owner, filesystem := opaqueWireOperationsHarness(t)
	for _, directory := range []string{"/\xd6\xd0\xce\xc4", "/中文", "/%D6%D0%CE%C4"} {
		if err := filesystem.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	source := "/\xd6\xd0\xce\xc4/\xd6\xd0\xce\xc4.txt"
	target := "/\xd6\xd0\xce\xc4/literal%2F.txt"
	siblings := map[string]string{"/中文/literal%2F.txt": "unicode-parent", "/%D6%D0%CE%C4/literal%2F.txt": "percent-parent"}
	writeWireOwned(t, filesystem, siblings)
	writeWireOwned(t, filesystem, map[string]string{source: "owned-opaque"})
	query := url.Values{"action": {"rename"}, "destination": {files.DisplayPath(target)}, "destinationWirePath": {files.EncodeWirePath(target)}}
	response := h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, files.EncodeWirePath(source)+"?"+query.Encode(), nil, nil)
	if response.Code != 200 {
		t.Fatalf("patch: %d %s", response.Code, response.Body.String())
	}
	if response.Header().Get("X-Resource-Destination-WirePath") != files.EncodeWirePath(target) || response.Header().Get("X-Resource-Destination") != url.PathEscape(files.DisplayPath(target)) {
		t.Fatalf("patch destination ACK lost actual identity: %v", response.Header())
	}
	assertWireOwned(t, filesystem, map[string]string{target: "owned-opaque"})
	assertWireOwned(t, filesystem, siblings)
	if exists, _ := afero.Exists(filesystem, source); exists {
		t.Fatal("patch left source")
	}
	// Conflict rename changes the destination. ACK must describe the actual
	// opaque target, not the originally requested destination or display sibling.
	writeWireOwned(t, filesystem, map[string]string{source: "owned-second"})
	query.Set("rename", "true")
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, files.EncodeWirePath(source)+"?"+query.Encode(), nil, nil)
	actual := "/\xd6\xd0\xce\xc4/literal%2F(1).txt"
	if response.Code != 200 || response.Header().Get("X-Resource-Destination-WirePath") != files.EncodeWirePath(actual) || response.Header().Get("X-Resource-Destination") != url.PathEscape(files.DisplayPath(actual)) {
		t.Fatalf("conflict rename actual destination ACK: %d %v %s", response.Code, response.Header(), response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{target: "owned-opaque", actual: "owned-second"})
	assertWireOwned(t, filesystem, siblings)
	copyTarget := "/\xd6\xd0\xce\xc4/copy%2F.txt"
	query.Set("action", "copy")
	query.Del("rename")
	query.Set("destination", files.DisplayPath(copyTarget))
	query.Set("destinationWirePath", files.EncodeWirePath(copyTarget))
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, files.EncodeWirePath(actual)+"?"+query.Encode(), nil, nil)
	if response.Code != 200 || response.Header().Get("X-Resource-Destination-WirePath") != files.EncodeWirePath(copyTarget) {
		t.Fatalf("legacy PATCH copy wire ACK: %d %v %s", response.Code, response.Header(), response.Body.String())
	}
	assertWireOwned(t, filesystem, map[string]string{actual: "owned-second", copyTarget: "owned-second"})
}

func TestWireOperationsDurableCheckpointAndRetryDoNotAliasSiblings(t *testing.T) {
	// In-memory byte identities are portable; this does not claim that Windows
	// can create invalid-UTF8 filenames on its native filesystem.
	filesystem := afero.NewMemMapFs()
	source, target := "/\xd6\xd0\xce\xc4.txt", "/to-\xd6\xd0\xce\xc4.txt"
	writeWireOwned(t, filesystem, map[string]string{source: "data", target: "data", "/中文.txt": "data", "/to-中文.txt": "data"})
	stamp := time.Unix(1700000000, 0)
	for _, name := range []string{source, "/中文.txt"} {
		if err := filesystem.Chtimes(name, stamp, stamp); err != nil {
			t.Fatal(err)
		}
	}
	item := fileTransferItem{From: source, To: target, Size: 4}
	key, _, err := fileTransferIdentity(filesystem, item)
	if err != nil {
		t.Fatal(err)
	}
	for _, char := range key {
		if char > 127 {
			t.Fatalf("checkpoint must be ASCII, key bytes=%x", []byte(key))
		}
	}
	encoded, _ := json.Marshal(fileTransferCheckpoint{Completed: map[string]bool{key: true}})
	var checkpoint fileTransferCheckpoint
	if err := json.Unmarshal(encoded, &checkpoint); err != nil {
		t.Fatal(err)
	}
	if !completedCheckpoint(filesystem, checkpoint.Completed, item) {
		t.Fatal("checkpoint lost exact source identity during JSON round trip")
	}
	if completedCheckpoint(filesystem, checkpoint.Completed, fileTransferItem{From: "/中文.txt", To: "/to-中文.txt", Size: 4}) {
		t.Fatal("checkpoint aliases a Unicode sibling")
	}
	args, _ := json.Marshal(fileTransferTaskArgs{Items: []fileTransferItem{item}})
	result := marshalFileTransferResult(fileTransferResult{Completed: []fileTransferResultItem{{From: source, To: target}}})
	retry, err := resumeFileTransferArgs(&tasks.Task{Args: args, Result: result})
	if err != nil {
		t.Fatal(err)
	}
	var restored fileTransferTaskArgs
	if err := json.Unmarshal(retry, &restored); err != nil || !completedPath(restored.CompletedPaths, item) {
		t.Fatalf("retry=%s err=%v", retry, err)
	}
	if err := filesystem.Remove(source); err != nil {
		t.Fatal(err)
	}
	if !completedCheckpoint(filesystem, checkpoint.Completed, item) {
		t.Fatal("completed move cannot restore its exact missing source")
	}
	if completedPath(restored.CompletedPaths, fileTransferItem{From: "/中文.txt", To: "/to-中文.txt"}) {
		t.Fatal("retry completed paths alias display siblings")
	}
	failure := marshalFileTransferResult(fileTransferResult{Failed: []fileTransferFailure{{From: source, To: target, Error: "owned failure"}}})
	var failed fileTransferResult
	if err := json.Unmarshal(failure, &failed); err != nil || len(failed.Failed) != 1 || failed.Failed[0].From != source || failed.Failed[0].To != target {
		t.Fatalf("failed result identity=%s err=%v", failure, err)
	}
}

func TestWireOperationsOldTaskLiteralCompatibilityAndAmbiguousFailClosed(t *testing.T) {
	for _, literal := range []string{"/中文.txt", "/literal%2F.txt", "/files/literal%2F.txt"} {
		payload, _ := json.Marshal(map[string]any{"items": []map[string]string{{"from": literal, "to": "/target"}}})
		var args fileTransferTaskArgs
		if err := json.Unmarshal(payload, &args); err != nil || args.Items[0].From != literal {
			t.Fatalf("old literal %q: %#v %v", literal, args, err)
		}
	}
	for _, payload := range []string{`{"items":[{"from":"/lost�.txt","to":"/target"}]}`, `{"kind":"resources","paths":["/lost�.txt"]}`,
		`{"pathEncoding":"unknown","items":[]}`, `{"pathEncoding":"unknown","kind":"resources"}`,
		`{"pathEncoding":"wire-v1","items":[{"from":"/source","to":"/target"}]}`, `{"pathEncoding":"wire-v1","paths":["/source"]}`} {
		var err error
		if strings.Contains(payload, "items") {
			var args fileTransferTaskArgs
			err = json.Unmarshal([]byte(payload), &args)
		} else {
			var args pendingDeletionArgs
			err = json.Unmarshal([]byte(payload), &args)
		}
		if err == nil {
			t.Fatalf("ambiguous old task must require recreation: %s", payload)
		}
	}
	// New, explicitly created U+FFFD is a legal Unicode filename, protected by wire identity.
	encoded, err := json.Marshal(fileTransferTaskArgs{Items: []fileTransferItem{{From: "/legal�.txt", To: "/target%2F"}}})
	var restored fileTransferTaskArgs
	if err != nil || json.Unmarshal(encoded, &restored) != nil || restored.Items[0].From != "/legal�.txt" {
		t.Fatalf("new Unicode replacement character: %s %v", encoded, err)
	}
	if !utf8.Valid(encoded) || !bytes.Contains(encoded, []byte(`"pathEncoding":"wire-v1"`)) {
		t.Fatalf("missing durable encoding marker: %s", encoded)
	}
}

func TestWireOperationsInvalidBoundariesNeverWrite(t *testing.T) {
	h, owner, _ := wireOperationsHarness(t)
	writeWireOwned(t, h.fs[owner.ID], map[string]string{"/source": "owned"})
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	for _, wire := range []string{"/bad%GG", "/bad%", "/source%2Fchild", "/bad%00", "/../source", "/%2e%2e/source", "//host/source"} {
		t.Run(wire, func(t *testing.T) {
			body, _ := json.Marshal(map[string]any{"action": "copy", "items": []map[string]string{{"fromWirePath": wire, "toWirePath": "/target"}}})
			response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
			if response.Code != 400 {
				t.Fatalf("transfer invalid wire: %d %s", response.Code, response.Body.String())
			}
			body, _ = json.Marshal(map[string]any{"kind": "resources", "wirePaths": []string{wire}})
			response = h.request(t, owner.ID, pendingDeletionHandler(runner), http.MethodPost, "/deletions", bytes.NewReader(body), nil)
			if response.Code != 400 {
				t.Fatalf("delete invalid wire: %d %s", response.Code, response.Body.String())
			}
			query := url.Values{"action": {"rename"}, "destinationWirePath": {wire}}
			response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source?"+query.Encode(), nil, nil)
			if response.Code != 400 {
				t.Fatalf("patch invalid wire: %d %s", response.Code, response.Body.String())
			}
		})
	}
	for _, body := range []string{
		`{"action":"copy","items":[{"from":"/other","fromWirePath":"/source","toWirePath":"/target"}]}`,
		`{"action":"copy","items":[{"fromWirePath":"/source","to":"/other","toWirePath":"/target"}]}`,
		`{"action":"copy","items":[{"from":"/source","fromWirePath":"","to":"/target"}]}`,
		`{"action":"copy","items":[{"from":"/source","to":"/target","toWirePath":null}]}`,
	} {
		response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", strings.NewReader(body), nil)
		if response.Code != 400 {
			t.Fatalf("mismatch transfer: %d %s", response.Code, response.Body.String())
		}
	}
	for _, body := range []string{`{"kind":"resources","paths":["/other"],"wirePaths":["/source"]}`, `{"kind":"resources","paths":[],"wirePaths":["/source"]}`, `{"kind":"resources","paths":[""],"wirePaths":["/source"]}`, `{"kind":"trash-all","wirePaths":["/source"]}`} {
		response := h.request(t, owner.ID, pendingDeletionHandler(runner), http.MethodPost, "/deletions", strings.NewReader(body), nil)
		if response.Code != 400 {
			t.Fatalf("mismatch delete: %d %s", response.Code, response.Body.String())
		}
	}
	query := url.Values{"action": {"rename"}, "destination": {"/other"}, "destinationWirePath": {"/target"}}
	response := h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source?"+query.Encode(), nil, nil)
	if response.Code != 400 {
		t.Fatalf("mismatch patch: %d %s", response.Code, response.Body.String())
	}
	query = url.Values{"action": {"rename"}, "destination": {"/target"}, "destinationWirePath": {""}}
	response = h.request(t, owner.ID, resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/source?"+query.Encode(), nil, nil)
	if response.Code != 400 || strings.Contains(response.Body.String(), "<nil>") {
		t.Fatalf("empty destination wire: %d %s", response.Code, response.Body.String())
	}
	assertWireOwned(t, h.fs[owner.ID], map[string]string{"/source": "owned"})
	all, err := h.storage.Tasks.List(owner.ID, false)
	if err != nil || len(all) != 0 {
		t.Fatalf("invalid requests enqueued tasks: %#v %v", all, err)
	}
}

func TestWireOperationsLegacyCheckpointCannotSkipDisplaySibling(t *testing.T) {
	fs := afero.NewMemMapFs()
	writeWireOwned(t, fs, map[string]string{"/lost�.txt": "keep", "/target": "keep"})
	legacy := fmt.Sprintf("%s\x00%s\x00%d\x00%d\x00%t", "/lost�.txt", "/target", 4, 0, false)
	if err := fs.Remove("/lost�.txt"); err != nil {
		t.Fatal(err)
	}
	if completedCheckpoint(fs, map[string]bool{legacy: true}, fileTransferItem{From: "/lost�.txt", To: "/target", Size: 4}) {
		t.Fatal("ambiguous old key skips/deletes a replacement-character sibling")
	}
	for _, source := range []string{"/中文.txt", "/literal%2F.txt", "/files/literal%2F.txt"} {
		key := fmt.Sprintf("%s\x00/target\x004\x000\x00false", source)
		encoded, err := json.Marshal(fileTransferCheckpoint{Completed: map[string]bool{key: true, legacy: true, "unknown-key": true}})
		var checkpoint fileTransferCheckpoint
		if err != nil || json.Unmarshal(encoded, &checkpoint) != nil || len(checkpoint.Completed) != 1 {
			t.Fatalf("legacy checkpoint=%s err=%v", encoded, err)
		}
		if !completedCheckpoint(fs, checkpoint.Completed, fileTransferItem{From: source, To: "/target", Size: 4}) {
			t.Fatalf("strict legacy literal checkpoint lost: %q", source)
		}
	}
}

func TestWireOperationsRetryPersistedCompletedMoveKeepsExactIdentity(t *testing.T) {
	for _, opaque := range []bool{false, true} {
		name := "unicode-literal-percent"
		if opaque {
			name = "opaque-os-bytes"
		}
		t.Run(name, func(t *testing.T) {
			var h *trashHTTPHarness
			var owner *users.User
			var filesystem afero.Fs
			source, target := "/合法�%2F.txt", "/to-合法�%2F.txt"
			if opaque {
				h, owner, filesystem = opaqueWireOperationsHarness(t)
				source, target = "/\xd6\xd0\xce\xc4.txt", "/to-\xd6\xd0\xce\xc4.txt"
			} else {
				h, owner, filesystem = wireOperationsHarness(t)
			}
			writeWireOwned(t, filesystem, map[string]string{target: "owned", "/中文.txt": "decoy"})
			args, _ := json.Marshal(fileTransferTaskArgs{Items: []fileTransferItem{{From: source, To: target, Size: 5}}})
			original, err := h.storage.Tasks.New(owner.ID, owner.Username, tasks.TypeFileMove, "owned interrupted move", args, "")
			if err != nil {
				t.Fatal(err)
			}
			original.Status = tasks.StatusInterrupted
			original.Result = marshalFileTransferResult(fileTransferResult{Completed: []fileTransferResultItem{{From: source, To: target}}})
			if err := h.storage.Tasks.Update(original); err != nil {
				t.Fatal(err)
			}
			// Recreate runtime and fetch the durable task through the HTTP retry path.
			runner, err := tasks.NewRuntime(h.storage.Tasks)
			if err != nil {
				t.Fatal(err)
			}
			response := h.request(t, owner.ID, taskRetryHandler(runner), http.MethodPost, "/tasks/"+original.ID+"/retry", nil, map[string]string{"id": original.ID})
			if response.Code != 202 {
				t.Fatalf("retry status=%d %s", response.Code, response.Body.String())
			}
			var retry tasks.Task
			if err := json.Unmarshal(response.Body.Bytes(), &retry); err != nil {
				t.Fatal(err)
			}
			done := waitForHTTPTask(t, h, retry.ID, tasks.StatusCompleted)
			if done.RetryOf != original.ID || done.ProcessedItems != 1 {
				t.Fatalf("retry=%#v", done)
			}
			assertWireOwned(t, filesystem, map[string]string{target: "owned", "/中文.txt": "decoy"})
		})
	}
}

func TestWireOperationsHTTPKeepsLegacyLiteralPercentAndNewReplacementCharacter(t *testing.T) {
	h, owner, _ := wireOperationsHarness(t)
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	for index, item := range []struct {
		source, from string
		withWire     bool
	}{
		{"/literal%2F.txt", "/literal%2F.txt", false},
		{"/legal�.txt", "/legal�.txt", false},
		{"/中文.txt", "/files/中文.txt", false},
		{"/files/literal%2F.txt", "/files/literal%2F.txt", true},
	} {
		if err := h.fs[owner.ID].MkdirAll("/files", 0o700); err != nil {
			t.Fatal(err)
		}
		writeWireOwned(t, h.fs[owner.ID], map[string]string{item.source: "owned"})
		target := fmt.Sprintf("/result-%d", index)
		entry := map[string]string{"from": item.from, "to": target}
		if item.withWire {
			entry["fromWirePath"], entry["toWirePath"] = files.EncodeWirePath(item.source), files.EncodeWirePath(target)
		}
		body, _ := json.Marshal(map[string]any{"action": "copy", "items": []map[string]string{entry}})
		response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
		if response.Code != 202 {
			t.Fatalf("legacy/new literal %q: %d %s", item.from, response.Code, response.Body.String())
		}
		var accepted tasks.Task
		if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
			t.Fatal(err)
		}
		done := waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
		var args fileTransferTaskArgs
		if err := json.Unmarshal(done.Args, &args); err != nil || args.Items[0].From != item.source {
			t.Fatalf("literal durable args=%s %v", done.Args, err)
		}
		assertWireOwned(t, h.fs[owner.ID], map[string]string{item.source: "owned", target: "owned"})
	}
	// The Web UTF8 compatibility route includes /files in addition to the
	// actual /files directory. Its explicit wire identity disambiguates both.
	body, _ := json.Marshal(map[string]any{"action": "copy", "items": []map[string]string{{
		"from": "/files/files/literal%252F.txt", "to": "/files/route-result", "fromWirePath": "/files/literal%252F.txt", "toWirePath": "/route-result",
	}}})
	response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
	if response.Code != 202 {
		t.Fatalf("actual /files Web route: %d %s", response.Code, response.Body.String())
	}
	var accepted tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
	assertWireOwned(t, h.fs[owner.ID], map[string]string{"/files/literal%2F.txt": "owned", "/route-result": "owned"})
}

func TestWireOperationsBoltReopenRestoresArgsResultAndCheckpoint(t *testing.T) {
	// Raw byte identity is represented in memory, while Bolt closes/reopens a
	// native database with a valid platform path on both operating systems.
	filesystem := afero.NewMemMapFs()
	source, target := "/\xd6\xd0\xce\xc4.txt", "/to-\xd6\xd0\xce\xc4.txt"
	writeWireOwned(t, filesystem, map[string]string{source: "owned", target: "owned"})
	item := fileTransferItem{From: source, To: target, Size: 5}
	key, _, err := fileTransferIdentity(filesystem, item)
	if err != nil {
		t.Fatal(err)
	}
	databasePath := filepath.Join(t.TempDir(), "owned-wire-tasks.db")
	db, err := storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if db != nil {
			_ = db.Close()
		}
	}()
	storage, err := bolt.NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	args, err := json.Marshal(fileTransferTaskArgs{Items: []fileTransferItem{item}})
	if err != nil {
		t.Fatal(err)
	}
	checkpoint, err := json.Marshal(fileTransferCheckpoint{Completed: map[string]bool{key: true}})
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{}
	for _, result := range []json.RawMessage{checkpoint, marshalFileTransferResult(fileTransferResult{Completed: []fileTransferResultItem{{From: source, To: target}}})} {
		task, err := storage.Tasks.New(1, "owned", tasks.TypeFileCopy, "owned wire task", args, "")
		if err != nil {
			t.Fatal(err)
		}
		task.Status, task.Result = tasks.StatusInterrupted, result
		if err := storage.Tasks.Update(task); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, task.ID)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = storm.Open(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	storage, err = bolt.NewStorage(db)
	if err != nil {
		t.Fatal(err)
	}
	for index, id := range ids {
		task, err := storage.Tasks.Get(1, id, false)
		if err != nil {
			t.Fatal(err)
		}
		encoded, err := resumeFileTransferArgs(task)
		if err != nil {
			t.Fatal(err)
		}
		var restored fileTransferTaskArgs
		if err := json.Unmarshal(encoded, &restored); err != nil || restored.Items[0].From != source || restored.Items[0].To != target {
			t.Fatalf("reopened args=%s err=%v", encoded, err)
		}
		if index == 0 && !completedCheckpoint(filesystem, restored.Completed, item) {
			t.Fatal("reopened checkpoint lost its identity")
		}
		if index == 1 && !completedPath(restored.CompletedPaths, item) {
			t.Fatal("reopened result lost its identity")
		}
	}
}

func TestWireOperationsWindowsRejectsInvalidUTF8BeforeNativeSiblingLookup(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Requires the real Windows UTF16 filesystem boundary")
	}
	h, owner, filesystem := wireOperationsHarness(t)
	protected := map[string]string{"/�.txt": "owned-replacement-decoy\x00exact-bytes", "/safe.txt": "owned-safe-source"}
	writeWireOwned(t, filesystem, protected)
	// Prove this is a real native alias, not merely a malformed-wire example.
	// The HTTP boundary must reject it before Windows converts it to U+FFFD.
	if content, err := afero.ReadFile(filesystem, "/\xff.txt"); err != nil || string(content) != protected["/�.txt"] {
		t.Fatalf("native invalid-UTF8 alias precondition: content=%q err=%v", content, err)
	}
	capabilities := h.request(t, owner.ID, clientCapabilitiesHandler, http.MethodGet, "/client-capabilities", nil, nil)
	var policy struct{ ResourceWireOperations bool }
	if capabilities.Code != 200 || json.Unmarshal(capabilities.Body.Bytes(), &policy) != nil || !policy.ResourceWireOperations {
		t.Fatalf("wire capability unavailable: %d %s", capabilities.Code, capabilities.Body.String())
	}
	runner, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	assertRejected := func(label string, handler handleFunc, method, target string, body any) {
		t.Helper()
		var encoded []byte
		if body != nil {
			encoded, err = json.Marshal(body)
			if err != nil {
				t.Fatal(err)
			}
		}
		response := h.request(t, owner.ID, handler, method, target, bytes.NewReader(encoded), nil)
		if response.Code == http.StatusAccepted {
			var accepted tasks.Task
			if json.Unmarshal(response.Body.Bytes(), &accepted) == nil && accepted.ID != "" {
				_, _ = runner.Cancel(owner.ID, accepted.ID, false)
			}
		}
		if response.Code != http.StatusForbidden {
			t.Fatalf("%s: invalid UTF8 must be rejected before lookup: %d %s", label, response.Code, response.Body.String())
		}
		assertWireOwned(t, filesystem, protected)
		all, err := h.storage.Tasks.List(owner.ID, false)
		if err != nil || len(all) != 0 {
			t.Fatalf("%s enqueued tasks: %#v %v", label, all, err)
		}
	}
	for _, action := range []string{"copy", "move"} {
		for _, invalidSource := range []bool{true, false} {
			from, to := "/safe.txt", "/%FF.txt"
			if invalidSource {
				from, to = "/%FF.txt", "/not-created-"+action+".txt"
			}
			body := map[string]any{"action": action, "items": []map[string]any{{"fromWirePath": from, "toWirePath": to, "overwrite": true}}}
			assertRejected(fmt.Sprintf("%s invalidSource=%t", action, invalidSource), fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", body)
		}
		if exists, _ := afero.Exists(filesystem, "/not-created-"+action+".txt"); exists {
			t.Fatal("rejected transfer created a destination")
		}
	}
	deletion := pendingDeletionHandler(runner)
	withCache := func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		d.fileCache = noopTrashFileCache{}
		return deletion(w, r, d)
	}
	assertRejected("delete", withCache, http.MethodPost, "/deletions/pending", map[string]any{"kind": "resources", "wirePaths": []string{"/%FF.txt"}})
	query := url.Values{"action": {"rename"}, "destinationWirePath": {"/not-created-patch.txt"}, "override": {"true"}}
	assertRejected("PATCH source", resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/%FF.txt?"+query.Encode(), nil)
	query.Set("destinationWirePath", "/%FF.txt")
	assertRejected("PATCH destination", resourcePatchHandler(noopTrashFileCache{}), http.MethodPatch, "/safe.txt?"+query.Encode(), nil)
	if exists, _ := afero.Exists(filesystem, "/not-created-patch.txt"); exists {
		t.Fatal("rejected PATCH created a destination")
	}
	// A genuinely valid U+FFFD wire must still work; blanket wire rejection
	// would hide the Windows alias bug while breaking legitimate filenames.
	body, _ := json.Marshal(map[string]any{"action": "copy", "items": []map[string]string{{"fromWirePath": files.EncodeWirePath("/�.txt"), "toWirePath": "/valid-copy.txt"}}})
	response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
	if response.Code != 202 {
		t.Fatalf("legitimate replacement-character wire: %d %s", response.Code, response.Body.String())
	}
	var accepted tasks.Task
	if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
	assertWireOwned(t, filesystem, protected)
	assertWireOwned(t, filesystem, map[string]string{"/valid-copy.txt": protected["/�.txt"]})
}
