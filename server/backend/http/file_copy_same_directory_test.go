package fbhttp

import (
	"bytes"
	"encoding/json"
	"net/http"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestCopySameDirectoryHTTPKeepsBothExactResources(t *testing.T) {
	for _, tc := range []struct {
		name, source, target string
		legacy, directory    bool
	}{
		{"legacy-percent-comma", "/literal%2F,中文.txt", "/literal%2F,中文(2).txt", true, false},
		{"wire-UTF8-replacement", "/�.txt", "/�(2).txt", false, false},
		{"wire-opaque", "/\xd6\xd0\xce\xc4.txt", "/\xd6\xd0\xce\xc4(2).txt", false, false},
		{"directory", "/folder", "/folder(2)", false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.name == "wire-opaque" && runtime.GOOS == "windows" {
				t.Skip("Windows rejects non-UTF8 filenames; this case requires byte-preserving filenames")
			}
			h, owner, filesystem := wireOperationsHarness(t)
			first := addVersionTestPath(tc.source)
			original, occupied, copied := tc.source, first, tc.target
			if tc.directory {
				for _, dir := range []string{original, occupied} {
					if err := filesystem.MkdirAll(dir+"/nested", 0o700); err != nil {
						t.Fatal(err)
					}
				}
				original += "/nested/child.txt"
				occupied += "/nested/child.txt"
				copied += "/nested/child.txt"
			}
			preserved := map[string]string{original: "owned-source", occupied: "occupied-sibling"}
			if tc.name == "wire-opaque" {
				preserved["/中文.txt"] = "unicode-source-sibling"
				preserved["/中文(2).txt"] = "unicode-target-sibling"
				preserved["/%D6%D0%CE%C4(2).txt"] = "literal-percent-target"
			}
			writeWireOwned(t, filesystem, preserved)
			// Keeping both needs create, not permission to overwrite the source.
			updateTransferActor(t, h, owner.ID, func(actor *users.User) { actor.Perm.Modify = false })
			runner, err := tasks.NewRuntime(h.storage.Tasks)
			if err != nil {
				t.Fatal(err)
			}
			item := map[string]any{"rename": true}
			if tc.legacy {
				item["from"], item["to"] = "/files"+files.EncodeWirePath(tc.source), "/files"+files.EncodeWirePath(tc.source)
			} else {
				item["fromWirePath"], item["toWirePath"] = files.EncodeWirePath(tc.source), files.EncodeWirePath(tc.source)
			}
			body, _ := json.Marshal(map[string]any{"action": "copy", "items": []any{item}})
			response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
			if response.Code != http.StatusAccepted {
				t.Fatalf("keep-both COPY: %d %s", response.Code, response.Body.String())
			}
			var accepted tasks.Task
			if err := json.Unmarshal(response.Body.Bytes(), &accepted); err != nil {
				t.Fatal(err)
			}
			done := waitForHTTPTask(t, h, accepted.ID, tasks.StatusCompleted)
			var saved fileTransferTaskArgs
			if err := json.Unmarshal(done.Args, &saved); err != nil || len(saved.Items) != 1 || saved.Items[0].From != tc.source || saved.Items[0].To != tc.target || saved.Items[0].Overwrite {
				t.Fatalf("durable copy identity=%s decoded=%#v err=%v", done.Args, saved, err)
			}
			var result fileTransferResult
			if err := json.Unmarshal(done.Result, &result); err != nil || len(result.Completed) != 1 || result.Completed[0].From != tc.source || result.Completed[0].To != tc.target {
				t.Fatalf("completed identity=%s err=%v", done.Result, err)
			}
			assertWireOwned(t, filesystem, preserved)
			assertWireOwned(t, filesystem, map[string]string{copied: "owned-source"})
			assertNoTransferTemp(t, filesystem)
		})
	}
}

func addVersionTestPath(source string) string {
	// Explicit fixture names, independent of the production suffix algorithm.
	switch source {
	case "/literal%2F,中文.txt":
		return "/literal%2F,中文(1).txt"
	case "/�.txt":
		return "/�(1).txt"
	case "/\xd6\xd0\xce\xc4.txt":
		return "/\xd6\xd0\xce\xc4(1).txt"
	default:
		return "/folder(1)"
	}
}

func TestCopySameDirectoryHTTPRejectsUnsafeTargets(t *testing.T) {
	for _, tc := range []struct {
		name, action, target                      string
		rename, overwrite, blockSuffix, directory bool
		status                                    int
	}{
		{name: "copy-without-keep-both", action: "copy", target: "/source.txt", status: 400},
		{name: "copy-overwrite-source", action: "copy", target: "/source.txt", overwrite: true, status: 400},
		{name: "move-with-keep-both", action: "move", target: "/source.txt", rename: true, status: 400},
		{name: "suffix-rule", action: "copy", target: "/source.txt", rename: true, blockSuffix: true, status: 403},
		{name: "directory-into-self", action: "copy", target: "/source.txt/nested", rename: true, directory: true, status: 400},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h, owner, filesystem := wireOperationsHarness(t)
			source := "/source.txt"
			original := source
			if tc.directory {
				if err := filesystem.MkdirAll(source, 0o700); err != nil {
					t.Fatal(err)
				}
				original += "/child.txt"
			}
			writeWireOwned(t, filesystem, map[string]string{original: "owned-source"})
			if tc.blockSuffix {
				updateTransferActor(t, h, owner.ID, func(actor *users.User) { actor.Rules = []rules.Rule{{Path: "/source(1).txt", Allow: false}} })
			}
			runner, err := tasks.NewRuntime(h.storage.Tasks)
			if err != nil {
				t.Fatal(err)
			}
			body, _ := json.Marshal(map[string]any{"action": tc.action, "items": []any{map[string]any{"fromWirePath": source, "toWirePath": tc.target, "rename": tc.rename, "overwrite": tc.overwrite}}})
			response := h.request(t, owner.ID, fileTransferTaskHandler(runner), http.MethodPost, "/resources/transfer", bytes.NewReader(body), nil)
			if response.Code != tc.status {
				t.Fatalf("status=%d %s want=%d", response.Code, response.Body.String(), tc.status)
			}
			all, err := h.storage.Tasks.List(owner.ID, false)
			if err != nil || len(all) != 0 {
				t.Fatalf("rejected request enqueued tasks=%#v err=%v", all, err)
			}
			assertWireOwned(t, filesystem, map[string]string{original: "owned-source"})
			if exists, _ := afero.Exists(filesystem, "/source(1).txt"); exists {
				t.Fatal("forbidden suffix target created")
			}
		})
	}
}
