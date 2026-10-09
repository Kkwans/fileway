package transport

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func nextCommandBatch(t *testing.T, broker *Broker, owner, id string) CommandBatch {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		batch, err := broker.PollCommand(owner, id)
		if err != nil {
			t.Fatal(err)
		}
		if len(batch.Lines) > 0 || batch.Done {
			return batch
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("command output did not arrive")
	return CommandBatch{}
}

func TestCommandUsesAuthenticatedSessionOpaqueDirectoryAndIncrementalOutput(t *testing.T) {
	release := make(chan struct{})
	var once sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.EscapedPath() != "/prefix/api/command/%D6%D0" || r.Header.Get("X-Auth") != "owned.token" {
			t.Error("command source identity changed")
		}
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		kind, raw, err := conn.Read(r.Context())
		if err != nil || kind != websocket.MessageText || string(raw) != "echo \"中文 +%\"" {
			t.Error("command bytes changed")
			return
		}
		if conn.Write(r.Context(), websocket.MessageText, []byte("first")) != nil {
			return
		}
		select {
		case <-release:
		case <-r.Context().Done():
			return
		}
		_ = conn.Write(r.Context(), websocket.MessageText, []byte("second"))
		_ = conn.Close(websocket.StatusNormalClosure, "output ended")
	}))
	defer server.Close()
	broker, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	defer once.Do(func() { close(release) })
	owner, err := broker.Open(server.URL+"/prefix", "owned.token", nil)
	if err != nil {
		t.Fatal(err)
	}
	call, cancel := context.WithCancel(context.Background())
	id, err := broker.StartCommand(call, owner, "/display", "/%D6%D0", "echo \"中文 +%\"")
	if err != nil {
		t.Fatal(err)
	}
	cancel() // A completed JNI start call does not own the long-lived connection.
	first := nextCommandBatch(t, broker, owner, id)
	if first.Done || len(first.Lines) != 1 || first.Lines[0] != "first" {
		t.Fatal(first)
	}
	other, err := broker.Open(server.URL, "other.account", nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = broker.PollCommand(other, id); err == nil {
		t.Fatal("cross-session poll accepted")
	}
	if err = broker.CancelCommand(other, id); err == nil {
		t.Fatal("cross-session cancel accepted")
	}
	once.Do(func() { close(release) })
	var lines []string
	for {
		batch := nextCommandBatch(t, broker, owner, id)
		lines = append(lines, batch.Lines...)
		if batch.Done {
			if batch.State != "closed" {
				t.Fatal(batch)
			}
			break
		}
	}
	if len(lines) != 1 || lines[0] != "second" {
		t.Fatal(lines)
	}
	if err = broker.CancelCommand(owner, id); err != nil {
		t.Fatal(err)
	}
}

func TestCommandOutputIsBoundedAndSessionCloseRetiresConnections(t *testing.T) {
	finished := make(chan struct{})
	disconnected := make(chan struct{})
	var connections atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		if _, _, err = conn.Read(r.Context()); err != nil {
			return
		}
		if connections.Add(1) == 2 {
			_ = conn.Write(r.Context(), websocket.MessageText, []byte("waiting"))
			_, _, _ = conn.Read(r.Context())
			close(disconnected)
			return
		}
		for index := 0; index < 600; index++ {
			if conn.Write(r.Context(), websocket.MessageText, []byte(strings.Repeat("x", 1000))) != nil {
				return
			}
		}
		_ = conn.Close(websocket.StatusNormalClosure, "")
		close(finished)
	}))
	defer server.Close()
	broker, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	owner, err := broker.Open(server.URL, "owned", nil)
	if err != nil {
		t.Fatal(err)
	}
	id, err := broker.StartCommand(context.Background(), owner, "/", "/", "echo owned")
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("output did not finish")
	}
	received := 0
	bytes := 0
	var dropped int64
	for {
		batch := nextCommandBatch(t, broker, owner, id)
		received += len(batch.Lines)
		for _, line := range batch.Lines {
			bytes += len(line)
		}
		dropped = batch.Dropped
		if batch.Done {
			break
		}
	}
	if received > commandQueueLines || bytes > commandQueueLimit || dropped <= 0 || int64(received)+dropped != 600 {
		t.Fatal(received, bytes, dropped)
	}
	id, err = broker.StartCommand(context.Background(), owner, "/", "/", "echo waiting")
	if err != nil {
		t.Fatal(err)
	}
	if batch := nextCommandBatch(t, broker, owner, id); len(batch.Lines) != 1 || batch.Lines[0] != "waiting" {
		t.Fatal(batch)
	}
	broker.CloseSession(owner)
	if _, err = broker.PollCommand(owner, id); err == nil {
		t.Fatal("closed-session output remained addressable")
	}
	select {
	case <-disconnected:
	case <-time.After(3 * time.Second):
		t.Fatal("closing session retained the WebSocket")
	}
}

func TestCommandCanceledBeforeStartDoesNotContactServer(t *testing.T) {
	broker, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	owner, err := broker.Open("http://fixture.invalid", "owned", nil)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = broker.StartCommand(ctx, owner, "/", "/", "echo owned"); err == nil {
		t.Fatal("pre-canceled command started")
	}
	for _, raw := range []string{"", "echo one\necho two", strings.Repeat("x", 8193)} {
		if _, err = broker.StartCommand(context.Background(), owner, "/", "/", raw); err == nil {
			t.Fatal("invalid single-command input accepted")
		}
	}
}
