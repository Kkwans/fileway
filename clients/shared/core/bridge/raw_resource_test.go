package bridge

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRawResourceBridgePreservesEmptyAndEncodedTextWithoutJSONWrapping(t *testing.T) {
	var expected []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil || !bytes.Equal(body, expected) || r.Header.Get("X-Auth") != "owned.raw.token" ||
			r.Header.Get("Content-Type") != "application/octet-stream" || r.URL.EscapedPath() != "/api/resources/%FF.txt" {
			t.Errorf("raw text or original account changed: bytes=%d err=%v", len(body), err)
		}
		w.Write([]byte("200 OK"))
	}))
	defer server.Close()
	e := &Engine{}
	defer e.Call([]byte(`{"op":"shutdown"}`))
	opened := command(t, e, Command{Op: "open", BaseURL: server.URL, Token: "owned.raw.token"})
	var session string
	if err := json.Unmarshal(opened["result"], &session); err != nil {
		t.Fatal(err)
	}
	for _, body := range [][]byte{{}, []byte("中文\r\n\"literal\"\\path"), {255, 254, 65, 0, 10, 0}, bytes.Repeat([]byte{'x'}, 1<<20)} {
		expected = body
		encoded := base64.StdEncoding.EncodeToString(body)
		reply := command(t, e, Command{Op: "request", Session: session, Method: "PUT", Endpoint: "/api/resources/%FF.txt", BodyBase64: &encoded})
		if string(reply["ok"]) != "true" {
			t.Fatal(reply)
		}
	}
	encoded := ""
	for _, method := range []string{"GET", "DELETE"} {
		reply := command(t, e, Command{Op: "request", Session: session, Method: method, Endpoint: "/api/resources/%FF.txt", BodyBase64: &encoded})
		if string(reply["ok"]) != "false" {
			t.Fatal("raw bytes accepted for unrelated method")
		}
	}
}
