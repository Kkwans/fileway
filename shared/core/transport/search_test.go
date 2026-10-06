package transport

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func searchFixture(t *testing.T, handler http.HandlerFunc) (*Broker, string) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = b.Close() })
	id, err := b.Open(server.URL+"/root", "fixture.account.token", nil)
	if err != nil {
		t.Fatal(err)
	}
	return b, id
}

func nextSearchBatch(t *testing.T, b *Broker, owner, id string) SearchBatch {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		batch, err := b.PollSearch(owner, id)
		if err != nil {
			t.Fatal(err)
		}
		if len(batch.Items) > 0 || batch.Done {
			return batch
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("search did not provide a result or terminal state")
	return SearchBatch{}
}

func searchResult(index int) string {
	item, _ := json.Marshal(SearchItem{Path: fmt.Sprintf("sub/电影🎬 %d.mkv", index), Name: fmt.Sprintf("电影🎬 %d.mkv", index), Size: 123})
	return `{"type":"result","item":` + string(item) + "}\n"
}

func TestSearchIsIncrementalAndPreservesSourceAndQuery(t *testing.T) {
	release := make(chan struct{})
	query := "电影🎬 & + #"
	b, owner := searchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.EscapedPath() != "/root/api/search/%D6%D0" || r.URL.Query().Get("query") != query || r.URL.Query().Get("scope") != "recursive" || r.Header.Get("X-Auth") != "fixture.account.token" {
			t.Errorf("search source/query changed: %s", r.URL.EscapedPath())
		}
		w.Header().Set("Content-Type", "application/x-ndjson; charset=utf-8")
		fmt.Fprint(w, "\n\r\n"+searchResult(1))
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
			return
		}
		fmt.Fprint(w, searchResult(2)+`{"type":"summary","reason":"completed","count":2}`+"\n")
	})
	call, cancel := context.WithCancel(context.Background())
	id, err := b.StartSearch(call, owner, "", "/%D6%D0", query, "recursive")
	if err != nil {
		t.Fatal(err)
	}
	cancel() // Only this short JNI call ends, not its session-owned stream.
	first := nextSearchBatch(t, b, owner, id)
	if first.Done || len(first.Items) != 1 || first.Items[0].Path != "sub/电影🎬 1.mkv" || first.Count != 1 {
		t.Fatal("buffered the full search or altered metadata", first)
	}
	close(release)
	total := len(first.Items)
	for {
		batch := nextSearchBatch(t, b, owner, id)
		total += len(batch.Items)
		if batch.Done {
			if batch.State != "finished" || batch.Summary == nil || batch.Summary.Reason != "completed" || batch.Count != 2 || total != 2 {
				t.Fatal(batch, total)
			}
			break
		}
	}
	if _, err := b.PollSearch(owner, id); err == nil {
		t.Fatal("terminal search handle was not released")
	}
}

func TestSearchDistinguishesSummaryAndTransportFailures(t *testing.T) {
	for _, tc := range []struct {
		name, body, reason string
		failed             bool
	}{
		{"completed-empty", `{"type":"summary","reason":"completed"}`, "completed", false},
		{"limit", searchResult(1) + `{"type":"summary","reason":"limit","count":1}`, "limit", false},
		// NAS can count a result which its timeout prevented from being sent.
		{"timeout-partial", searchResult(1) + `{"type":"summary","reason":"timeout","count":2}`, "timeout", false},
		{"server-error", searchResult(1) + `{"type":"summary","reason":"error","count":1,"error":"private server path"}`, "error", true},
		{"missing-summary", searchResult(1), "", true},
		{"malformed", searchResult(1) + `{"type":`, "", true},
		{"count-mismatch", searchResult(1) + `{"type":"summary","reason":"completed","count":2}`, "", true},
		{"unknown-event", `{"type":"other"}`, "", true},
		{"invalid-path", `{"type":"result","item":{"path":"../other-account","name":"x"}}`, "", true},
		{"oversized-record", `{"type":"result","item":{"name":"` + strings.Repeat("x", searchRecordLimit) + `"}}`, "", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b, owner := searchFixture(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/x-ndjson")
				fmt.Fprint(w, tc.body)
			})
			id, err := b.StartSearch(context.Background(), owner, "/", "", "film", "current")
			if err != nil {
				t.Fatal(err)
			}
			for {
				batch := nextSearchBatch(t, b, owner, id)
				if !batch.Done {
					continue
				}
				if (batch.State == "failed") != tc.failed || (batch.Failure != "") != tc.failed || strings.Contains(batch.Failure, "private server path") {
					t.Fatal(batch)
				}
				if tc.reason != "" && (batch.Summary == nil || batch.Summary.Reason != tc.reason) {
					t.Fatal(batch)
				}
				break
			}
		})
	}
}

func TestSearchQueueHasBackpressureAndCancelStopsTheSource(t *testing.T) {
	canceled := make(chan struct{})
	b, owner := searchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		for index := 0; index < 300; index++ {
			fmt.Fprint(w, searchResult(index))
		}
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(canceled)
	})
	id, err := b.StartSearch(context.Background(), owner, "/", "", "film", "recursive")
	if err != nil {
		t.Fatal(err)
	}
	b.mu.RLock()
	stream := b.searches[id]
	b.mu.RUnlock()
	deadline := time.Now().Add(3 * time.Second)
	for {
		stream.mu.Lock()
		count, queued := stream.count, len(stream.queue)
		stream.mu.Unlock()
		if queued == searchQueueSize {
			if count != searchQueueSize {
				t.Fatal("unbounded parsed metadata", count)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("search queue did not fill")
		}
		time.Sleep(time.Millisecond)
	}
	if err := b.CancelSearch(owner, id); err != nil {
		t.Fatal(err)
	}
	select {
	case <-canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("source did not receive cancellation while the parser was backpressured")
	}
	stream.mu.Lock()
	defer stream.mu.Unlock()
	if stream.state != "canceled" || len(stream.queue) != 0 {
		t.Fatal("canceled query retained results")
	}
}

func TestSearchOwnershipCloseAndHandleLimit(t *testing.T) {
	b, owner := searchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	})
	base, _ := b.session(owner)
	other, _ := b.Open(base.base.String(), "another.account.token", nil)
	var handles []string
	for index := 0; index < 4; index++ {
		id, err := b.StartSearch(context.Background(), owner, "/", "", "film", "current")
		if err != nil {
			t.Fatal(err)
		}
		handles = append(handles, id)
	}
	if _, err := b.StartSearch(context.Background(), owner, "/", "", "film", "current"); err == nil {
		t.Fatal("unbounded search handle creation")
	}
	if _, err := b.PollSearch(other, handles[0]); err == nil {
		t.Fatal("another account could read the query")
	}
	if err := b.CancelSearch(other, handles[0]); err == nil {
		t.Fatal("another account could cancel the query")
	}
	b.CloseSession(other)
	if _, err := b.PollSearch(owner, handles[0]); err != nil {
		t.Fatal("closing another account affected the query", err)
	}
	b.CloseSession(owner)
	for _, id := range handles {
		if _, err := b.PollSearch(owner, id); err == nil {
			t.Fatal("closed session exposed search metadata")
		}
		if err := b.CancelSearch(owner, id); err != nil {
			t.Fatal("retired handle cancellation was not idempotent")
		}
	}
}

func TestSearchRenewAndHTTPPermissionState(t *testing.T) {
	var renews atomic.Int32
	b, owner := searchFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/root/api/renew" {
			renews.Add(1)
			fmt.Fprint(w, "renewed.account.token")
			return
		}
		if r.URL.Query().Get("query") == "denied" {
			w.WriteHeader(403)
			fmt.Fprint(w, "private server details")
			return
		}
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.Header().Set("X-Renew-Token", "true")
		fmt.Fprint(w, `{"type":"summary","reason":"completed"}`)
	})
	id, _ := b.StartSearch(context.Background(), owner, "/", "", "film", "current")
	batch := nextSearchBatch(t, b, owner, id)
	token, _ := b.Token(owner)
	if !batch.Done || batch.State != "finished" || token != "renewed.account.token" || renews.Load() != 1 {
		t.Fatal(batch, renews.Load())
	}
	id, _ = b.StartSearch(context.Background(), owner, "/", "", "denied", "current")
	batch = nextSearchBatch(t, b, owner, id)
	if !batch.Done || batch.State != "failed" || batch.HTTPStatus != 403 || strings.Contains(batch.Failure, "private server details") {
		t.Fatal(batch)
	}
}
