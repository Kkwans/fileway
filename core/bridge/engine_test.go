package bridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func command(t *testing.T, e *Engine, c Command) map[string]json.RawMessage {
	t.Helper()
	data, _ := json.Marshal(c)
	out := make(map[string]json.RawMessage)
	if err := json.Unmarshal(e.Call(data), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestControlBoundaryPreservesUTF8AndSessionLifecycle(t *testing.T) {
	var seen string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { seen = r.URL.Path; w.Write([]byte("原生🎬")) }))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	out := command(t, e, Command{Op: "open", BaseURL: server.URL + "/路径", Token: "a.b.c"})
	var sid string
	json.Unmarshal(out["result"], &sid)
	if sid == "" {
		t.Fatal(out)
	}
	out = command(t, e, Command{Op: "request", Session: sid, Method: "GET", Endpoint: "/api/resources/%E7%94%B5%E5%BD%B1"})
	if string(out["ok"]) != "true" || seen != "/路径/api/resources/电影" {
		t.Fatal(out, seen)
	}
	var res struct{ Body string }
	json.Unmarshal(out["result"], &res)
	if res.Body != "原生🎬" {
		t.Fatal(res)
	}
	command(t, e, Command{Op: "close_session", Session: sid})
	out = command(t, e, Command{Op: "lease", Session: sid, Path: "/电影.mkv"})
	if string(out["ok"]) != "false" {
		t.Fatal("closed session reopened", out)
	}
}

func TestInvalidCommandsAreBounded(t *testing.T) {
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	for _, data := range [][]byte{[]byte(`bad`), make([]byte, (1<<20)+1), []byte(`{"op":"unknown"}`)} {
		var out Envelope
		if err := json.Unmarshal(e.Call(data), &out); err != nil || out.OK || out.Error == "" {
			t.Fatal(err, out)
		}
	}
}
