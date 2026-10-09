package fbhttp

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/transfers"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/gorilla/mux"
	"github.com/spf13/afero"
)

func TestCancelUploadStopsBodyAndKeepsTusPart(t *testing.T) {
	for _, tus := range []bool{false, true} {
		t.Run(fmt.Sprintf("tus=%v", tus), func(t *testing.T) {
			h := nativeTusHarness(t)
			const id, target = "owned-slow-upload", "/slow-upload.bin"
			var upload handleFunc
			method := http.MethodPost
			if tus {
				expectTus(t, tusRequest(t, h, 1, http.MethodPost, target, id, 8, 0, nil), 201)
				upload = tusPatchHandler(nil)
				method = http.MethodPatch
			} else {
				upload = resourcePostHandler(noopTrashFileCache{})
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/cancel" {
					handle(transferCancelHandler, "", h.storage, h.server).ServeHTTP(w, mux.SetURLVars(r, map[string]string{"id": id}))
				} else {
					handle(withTransferCancellation(upload, transfers.KindUpload), "", h.storage, h.server).ServeHTTP(w, r)
				}
			}))
			defer server.Close()
			reader, writer := io.Pipe()
			defer reader.Close()
			defer writer.Close()
			request, _ := http.NewRequest(method, server.URL+target+"?transfer="+id, reader)
			request.ContentLength = 8
			request.Header.Set("X-Auth", signedTrashHTTPToken(t, 1))
			request.Header.Set("X-Transfer-ID", id)
			if tus {
				request.Header.Set("Upload-Offset", "0")
				request.Header.Set("Tus-Resumable", "1.0.0")
				request.Header.Set("Content-Type", "application/offset+octet-stream")
			}
			client := &http.Client{Timeout: 5 * time.Second}
			done := make(chan struct{})
			go func() {
				defer close(done)
				response, err := client.Do(request)
				if err == nil {
					io.Copy(io.Discard, response.Body)
					response.Body.Close()
				}
			}()
			if _, err := writer.Write([]byte("abcd")); err != nil {
				t.Fatal(err)
			}
			deadline := time.Now().Add(2 * time.Second)
			for {
				item, err := h.storage.Transfers.Get(1, id, false)
				name := target
				if tus {
					name, _ = tusPart(ownedTusRow(t, h, target, id))
				}
				info, statErr := h.fs[1].Stat(name)
				if err == nil && item.Status == transfers.StatusRunning && statErr == nil && info.Size() == 4 {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("upload did not start")
				}
				time.Sleep(10 * time.Millisecond)
			}
			cancel, _ := http.NewRequest(http.MethodPost, server.URL+"/cancel", nil)
			cancel.Header.Set("X-Auth", signedTrashHTTPToken(t, 1))
			response, err := client.Do(cancel)
			if err != nil {
				t.Fatal(err)
			}
			io.Copy(io.Discard, response.Body)
			response.Body.Close()
			if response.StatusCode != http.StatusAccepted {
				t.Fatal("cancel was not accepted", response.StatusCode)
			}
			deadline = time.Now().Add(2 * time.Second)
			for {
				item, err := h.storage.Transfers.Get(1, id, false)
				if err == nil && item.Status == transfers.StatusCanceled {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("upload body did not stop: %+v %v", item, err)
				}
				time.Sleep(10 * time.Millisecond)
			}
			writer.Close()
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("upload HTTP request remained alive")
			}
			if exists, _ := afero.Exists(h.fs[1], target); exists {
				t.Fatal("canceled upload published a file")
			}
			if tus {
				row := ownedTusRow(t, h, target, id)
				part, err := tusPart(row)
				if err != nil {
					t.Fatal(err)
				}
				data, err := afero.ReadFile(h.fs[1], part)
				if err != nil || !bytes.Equal(data, []byte("abcd")) {
					t.Fatalf("TUS part lost: %q %v", data, err)
				}
				// Stop is not DELETE: the original session can resume its exact part.
				resume := httptest.NewRequest(http.MethodPatch, target+"?transfer="+id, bytes.NewReader([]byte("efgh")))
				resume.Header = request.Header.Clone()
				resume.Header.Set("Upload-Offset", "4")
				ack := httptest.NewRecorder()
				handle(withTransferCancellation(tusPatchHandler(nil), transfers.KindUpload), "", h.storage, h.server).ServeHTTP(ack, resume)
				if ack.Code != 204 {
					t.Fatal("original TUS resume failed", ack.Code, ack.Body.String())
				}
				data, err = afero.ReadFile(h.fs[1], target)
				if err != nil || string(data) != "abcdefgh" {
					t.Fatal("resumed file differs", string(data), err)
				}
			}
		})
	}
}

func TestRawTransferPartialResponsesDoNotCompleteWholeFile(t *testing.T) {
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		t.Run(method, func(t *testing.T) {
			h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner", Perm: users.Permissions{Download: true}})
			owner := firstTrashHTTPUser(h)
			if err := afero.WriteFile(h.fs[owner.ID], "/file.bin", []byte("0123456789"), 0600); err != nil {
				t.Fatal(err)
			}
			done := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				defer close(done)
				handle(withTransferCancellation(rawHandler, transfers.KindDownload), "", h.storage, h.server).ServeHTTP(w, r)
			}))
			defer server.Close()
			req, _ := http.NewRequest(method, server.URL+"/file.bin?transfer=partial-owned", nil)
			req.Header.Set("X-Auth", signedTrashHTTPToken(t, owner.ID))
			if method == http.MethodGet {
				req.Header.Set("Range", "bytes=0-0")
			}
			response, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil {
				t.Fatal(err)
			}
			<-done
			item, err := h.storage.Transfers.Get(owner.ID, "partial-owned", false)
			if err != nil {
				t.Fatal(err)
			}
			if item.Status == transfers.StatusCompleted || item.BytesTransferred != int64(len(body)) {
				t.Fatalf("partial response invented completion: status=%s bytes=%d actual=%d", item.Status, item.BytesTransferred, len(body))
			}
		})
	}
}

func TestRawTransferConfirmsFullResponseAndContiguousResume(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner", Perm: users.Permissions{Download: true}})
	if err := afero.WriteFile(h.fs[1], "/file.bin", []byte("0123456789"), 0600); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() { finished <- struct{}{} }()
		handle(withTransferCancellation(rawHandler, transfers.KindDownload), "", h.storage, h.server).ServeHTTP(w, r)
	}))
	defer server.Close()
	get := func(id, span, modified string) *http.Response {
		t.Helper()
		req, _ := http.NewRequest(http.MethodGet, server.URL+"/file.bin?transfer="+id, nil)
		req.Header.Set("X-Auth", signedTrashHTTPToken(t, 1))
		if span != "" {
			req.Header.Set("Range", span)
		}
		if modified != "" {
			req.Header.Set("If-Modified-Since", modified)
		}
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, response.Body)
		response.Body.Close()
		<-finished
		return response
	}
	full := get("full-owned", "", "")
	item, err := h.storage.Transfers.Get(1, "full-owned", false)
	if err != nil || full.StatusCode != 200 || item.Status != transfers.StatusCompleted || item.BytesTransferred != 10 {
		t.Fatal("full response did not complete", item, err)
	}
	get("resume-owned", "bytes=0-3", "")
	item, err = h.storage.Transfers.Get(1, "resume-owned", false)
	if err != nil || item.Status == transfers.StatusCompleted || item.BytesTransferred != 4 {
		t.Fatal("prefix was not retained", item, err)
	}
	get("resume-owned", "bytes=4-", "")
	item, err = h.storage.Transfers.Get(1, "resume-owned", false)
	if err != nil || item.Status != transfers.StatusCompleted || item.BytesTransferred != 10 {
		t.Fatal("confirmed continuation did not complete", item, err)
	}
	get("tail-owned", "bytes=5-9", "")
	item, err = h.storage.Transfers.Get(1, "tail-owned", false)
	if err != nil || item.Status == transfers.StatusCompleted || item.BytesTransferred != 0 {
		t.Fatal("tail invented missing prefix", item, err)
	}
	conditional := get("conditional-owned", "", full.Header.Get("Last-Modified"))
	item, err = h.storage.Transfers.Get(1, "conditional-owned", false)
	if err != nil || conditional.StatusCode != 304 || item.Status == transfers.StatusCompleted || item.BytesTransferred != 0 {
		t.Fatal("304 invented downloaded bytes", conditional.StatusCode, item, err)
	}
}

func TestCancelRawTransferStopsRealHTTPStream(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{ID: 1, Username: "owner", Perm: users.Permissions{Download: true}}, users.User{ID: 2, Username: "other"})
	owner := h.users[1]
	payload := strings.Repeat("x", 16<<20)
	if err := afero.WriteFile(h.fs[owner.ID], "/slow.bin", []byte(payload), 0600); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/cancel" {
			handle(transferCancelHandler, "", h.storage, h.server).ServeHTTP(w, mux.SetURLVars(r, map[string]string{"id": "slow-owned"}))
		} else {
			handle(withTransferCancellation(rawHandler, transfers.KindDownload), "", h.storage, h.server).ServeHTTP(w, r)
		}
	}))
	defer server.Close()
	client := &http.Client{Timeout: 5 * time.Second}
	req, _ := http.NewRequest(http.MethodGet, server.URL+"/slow.bin?transfer=slow-owned", nil)
	req.Header.Set("X-Auth", signedTrashHTTPToken(t, owner.ID))
	response, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	prefix := make([]byte, 1024)
	if _, err := io.ReadFull(response.Body, prefix); err != nil {
		t.Fatal(err)
	}
	foreign := h.request(t, 2, transferCancelHandler, http.MethodPost, "/cancel", nil, map[string]string{"id": "slow-owned"})
	if foreign.Code != http.StatusNotFound {
		t.Fatal("another owner controlled the stream", foreign.Code)
	}
	cancel, _ := http.NewRequest(http.MethodPost, server.URL+"/cancel", nil)
	cancel.Header.Set("X-Auth", signedTrashHTTPToken(t, owner.ID))
	ack, err := client.Do(cancel)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, ack.Body)
	ack.Body.Close()
	if ack.StatusCode != http.StatusAccepted {
		t.Fatal("cancel status", ack.StatusCode)
	}
	remaining, _ := io.Copy(io.Discard, response.Body)
	if remaining+int64(len(prefix)) >= int64(len(payload)) {
		t.Fatal("cancel only changed history; entire HTTP stream continued")
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		item, err := h.storage.Transfers.Get(owner.ID, "slow-owned", false)
		if err != nil {
			t.Fatal(err)
		}
		if item.Status == transfers.StatusCanceled {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("stream stopped without canceled state: %+v", item)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestTransferTrackerRecordsHTTPFailureInsteadOfCompletion(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		ID:       1,
		Username: "owner",
		Perm:     users.Permissions{Download: true},
	})
	owner := firstTrashHTTPUser(h)
	item, err := h.storage.Transfers.New(owner.ID, transfers.KindDownload, "missing.txt", "/missing.txt", 12)
	if err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodGet, "/missing.txt?transfer="+item.ID, nil)
	recorder := httptest.NewRecorder()
	tracker := &transferTracker{
		ResponseWriter: recorder,
		request:        request,
		data:           &data{store: h.storage, user: owner},
		item:           item,
	}
	tracker.WriteHeader(http.StatusNotFound)
	tracker.finish(0, nil)
	tracker.finish(http.StatusOK, nil)

	stored, err := h.storage.Transfers.Get(owner.ID, item.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != transfers.StatusFailed {
		t.Fatalf("status = %q, want failed", stored.Status)
	}
	if stored.Error != http.StatusText(http.StatusNotFound) {
		t.Fatalf("error = %q, want %q", stored.Error, http.StatusText(http.StatusNotFound))
	}
}

func TestTransferTrackerMarksSuccessfulResponseCompleted(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{
		ID:       2,
		Username: "owner",
		Perm:     users.Permissions{Download: true},
	})
	owner := firstTrashHTTPUser(h)
	item, err := h.storage.Transfers.New(owner.ID, transfers.KindDownload, "ok.txt", "/ok.txt", 4)
	if err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodGet, "/ok.txt?transfer="+item.ID, nil)
	recorder := httptest.NewRecorder()
	tracker := &transferTracker{
		ResponseWriter: recorder,
		request:        request,
		data:           &data{store: h.storage, user: owner},
		item:           item,
	}
	if _, err := tracker.Write([]byte("done")); err != nil {
		t.Fatal(err)
	}
	tracker.finish(0, nil)

	stored, err := h.storage.Transfers.Get(owner.ID, item.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Status != transfers.StatusCompleted {
		t.Fatalf("status = %q, want completed", stored.Status)
	}
	if stored.BytesTransferred != stored.BytesTotal {
		t.Fatalf("bytes transferred = %d, want %d", stored.BytesTransferred, stored.BytesTotal)
	}
}
