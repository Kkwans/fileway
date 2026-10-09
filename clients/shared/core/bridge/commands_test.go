package bridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
	"github.com/coder/websocket"
)

func TestCommandControlSurvivesJNIStartAndRejectsPreCanceledStart(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		if _, _, err = conn.Read(r.Context()); err != nil {
			return
		}
		_ = conn.Write(r.Context(), websocket.MessageText, []byte("owned output"))
		_ = conn.Close(websocket.StatusNormalClosure, "")
	}))
	defer server.Close()
	engine := &Engine{}
	defer engine.Call([]byte(`{"op":"shutdown"}`))
	opened := command(t, engine, Command{Op: "open", BaseURL: server.URL})
	var owner string
	if json.Unmarshal(opened["result"], &owner) != nil {
		t.Fatal(opened)
	}
	started := command(t, engine, Command{Op: "command_start", Session: owner, Path: "/", WirePath: "/", RawCommand: "echo owned"})
	var handle string
	if json.Unmarshal(started["result"], &handle) != nil || handle == "" {
		t.Fatal(started)
	}
	deadline := time.Now().Add(3 * time.Second)
	received := 0
	for {
		out := command(t, engine, Command{Op: "command_poll", Session: owner, CommandHandle: handle})
		var batch transport.CommandBatch
		if string(out["ok"]) != "true" || json.Unmarshal(out["result"], &batch) != nil {
			t.Fatal(out)
		}
		received += len(batch.Lines)
		if batch.Done {
			if received != 1 || batch.State != "closed" {
				t.Fatal(batch, received)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("JNI return canceled command output")
		}
		time.Sleep(time.Millisecond)
	}
	command(t, engine, Command{Op: "command_cancel", Session: owner, CommandHandle: handle})
	command(t, engine, Command{Op: "cancel", RequestID: "owned-canceled-command"})
	blocked := command(t, engine, Command{Op: "command_start", Session: owner, RequestID: "owned-canceled-command", Path: "/", WirePath: "/", RawCommand: "echo owned"})
	if string(blocked["ok"]) != "false" || calls.Load() != 1 {
		t.Fatal("pre-canceled command contacted source", blocked, calls.Load())
	}
}
