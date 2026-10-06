package transport

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestURLPreservesBaseAndWireBytes(t *testing.T) {
	b, err := BaseURL("https://example.test/nas/")
	if err != nil {
		t.Fatal(err)
	}
	for _, wire := range []string{"/%E4%B8%AD%E6%96%87/%25%23%3F.mkv", "/%D6%D0/film.mkv", "/literal%252Fname.mkv"} {
		raw, err := RawEndpoint("", wire)
		if err != nil {
			t.Fatal(err)
		}
		u, err := Endpoint(b, raw)
		if err != nil {
			t.Fatal(err)
		}
		if got := u.String(); got != "https://example.test/nas/api/raw"+wire {
			t.Fatalf("opaque path changed: %s", got)
		}
	}
	path, _ := RawEndpoint("/中文/a%2F?#.mkv", "")
	u, _ := Endpoint(b, path)
	if !strings.Contains(u.String(), "a%252F%3F%23.mkv") {
		t.Fatal(u)
	}
	for _, bad := range []string{"//evil.test/api/raw/x", "https://evil.test/api/raw/x", "/api/../raw/x", "/api/%2e%2e/raw/x"} {
		if _, err := Endpoint(b, bad); err == nil {
			t.Fatal("accepted", bad)
		}
	}
	for _, bad := range []string{"http://u:p@example.test", "http://example.test?q=1", "file:///tmp/a", "https://"} {
		if _, err := BaseURL(bad); err == nil {
			t.Fatal("accepted", bad)
		}
	}
	preview, err := PreviewEndpoint("/中文/a%2F?#.mkv", "")
	if err != nil || preview != "/api/preview/thumb/%E4%B8%AD%E6%96%87/a%252F%3F%23.mkv" {
		t.Fatal("preview path was not encoded exactly once", preview, err)
	}
}

func TestAuthenticatedRangeAndImmutableLease(t *testing.T) {
	data := strings.Repeat("0123456789", 1000)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/base/api/login" {
			w.Write([]byte("header.payload.signature"))
			return
		}
		if r.Header.Get("X-Auth") != "header.payload.signature" {
			w.WriteHeader(401)
			return
		}
		if r.URL.Path != "/base/api/raw/video.mkv" || r.URL.RawQuery != "" {
			t.Errorf("unexpected path %s", r.URL.String())
			w.WriteHeader(404)
			return
		}
		http.ServeContent(w, r, "video.mkv", time.Unix(0, 0), strings.NewReader(data))
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL+"/base", "", nil)
	login, err := b.Login(context.Background(), sid, "user", "secret")
	if err != nil || login.Status != 200 {
		t.Fatal(login, err)
	}
	lease, _ := b.Lease(sid, "/api/raw/video.mkv", true)
	for _, spec := range []string{"bytes=0-99", "bytes=9900-", "bytes=-100"} {
		r, _ := http.NewRequest("GET", lease, nil)
		r.Header.Set("Range", spec)
		res, err := http.DefaultClient.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		bytes, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 206 || len(bytes) != 100 || !strings.HasSuffix(res.Header.Get("Content-Range"), "/10000") {
			t.Fatal(spec, res.StatusCode, res.Header, len(bytes))
		}
	}
	res, err := http.Head(lease)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 || res.ContentLength != 10000 {
		t.Fatal(res)
	}
	other, _ := b.Open(server.URL+"/base", "different.account.token", nil)
	b.CloseSession(other)
	res, _ = http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatal("new session affected old lease")
	}
	b.CloseSession(sid)
	res, _ = http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 410 {
		t.Fatal("revoked session remained available")
	}
}

func TestRenewSingleFlightAndCredentialRedirect(t *testing.T) {
	var renews atomic.Int32
	var leaked atomic.Bool
	foreign := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		leaked.Store(r.Header.Get("X-Auth") != "")
		w.WriteHeader(200)
	}))
	defer foreign.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/renew":
			renews.Add(1)
			w.Write([]byte("new.account.token"))
		case "/api/redirect":
			http.Redirect(w, r, foreign.URL, http.StatusFound)
		default:
			if r.Header.Get("X-Auth") == "old.account.token" {
				w.Header().Set("X-Renew-Token", "true")
			}
			w.Write([]byte("{}"))
		}
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "old.account.token", nil)
	var group sync.WaitGroup
	for i := 0; i < 20; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := b.Request(context.Background(), sid, "GET", "/api/resources/", nil); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	token, _ := b.Token(sid)
	if token != "new.account.token" || renews.Load() != 1 {
		t.Fatal(token, renews.Load())
	}
	r, err := b.Request(context.Background(), sid, "GET", "/api/redirect", nil)
	if err != nil || r.Status != 302 || leaked.Load() {
		t.Fatal(r, err, "credential crossed origin")
	}
}

func TestRevokeCancelsStreamingAndRejectsUnknownOrigins(t *testing.T) {
	cancelled := make(chan struct{})
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-matroska")
		w.WriteHeader(200)
		fmt.Fprint(w, strings.Repeat("x", 128<<10))
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "a.b.c", nil)
	lease, _ := b.Lease(sid, "/api/raw/x", true)
	request, _ := http.NewRequest("GET", lease, nil)
	request.Header.Set("Origin", "https://foreign.test")
	res, _ := http.DefaultClient.Do(request)
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatal("browser-origin request accepted")
	}
	res, err := http.Get(lease)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	<-started
	b.Revoke(lease)
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream not cancelled")
	}
}

func TestMediaPermissionAndRangeErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/raw/denied" {
			w.WriteHeader(202)
			return
		}
		http.ServeContent(w, r, "x", time.Time{}, strings.NewReader("123"))
	}))
	defer server.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(server.URL, "a.b.c", nil)
	lease, _ := b.Lease(sid, "/api/raw/denied", true)
	res, _ := http.Get(lease)
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatal(res.StatusCode)
	}
	lease, _ = b.Lease(sid, "/api/raw/x", true)
	r, _ := http.NewRequest("GET", lease, nil)
	r.Header.Set("Range", "bytes=99-")
	res, _ = http.DefaultClient.Do(r)
	res.Body.Close()
	if res.StatusCode != 416 || res.Header.Get("Content-Range") != "bytes */3" {
		t.Fatal(res.StatusCode, res.Header)
	}
}
