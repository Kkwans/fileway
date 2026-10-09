package transport

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func uploadCall(t *testing.T, method, lease string, body io.Reader, headers map[string]string) *http.Response {
	t.Helper()
	r, err := http.NewRequest(method, lease, body)
	if err != nil {
		t.Fatal(err)
	}
	for name, value := range headers {
		r.Header.Set(name, value)
	}
	client := &http.Client{Timeout: 5 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	res, err := client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { res.Body.Close() })
	return res
}

func TestResourceUploadStreamsOriginalBytesBoundToOriginalSource(t *testing.T) {
	data := bytes.Repeat([]byte("upload +% #\x00"), 300000) // larger than the JNI command ceiling
	wire := "/%D6%D0/owned%252F%20%2B%25%23.bin"
	var calls atomic.Int32
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Method != "POST" || r.URL.EscapedPath() != "/nas/api/resources"+wire || r.URL.RawQuery != "override=false" {
			t.Error("upload target or method changed")
		}
		if r.Header.Get("X-Auth") != "owned.first.token" || r.Header.Get("X-Transfer-ID") != "owned-upload" || r.Header.Get("X-Upload-Batch-ID") != "owned-batch" {
			t.Error("upload ownership changed")
		}
		if r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" || r.ContentLength != int64(len(data)) {
			t.Error("foreign credentials or wrong body length forwarded")
		}
		body, err := io.ReadAll(r.Body)
		if err != nil || !bytes.Equal(data, body) {
			t.Error("original bytes changed", err)
		}
		w.Write([]byte("200 OK\n"))
	}))
	defer source.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(source.URL+"/nas", "owned.first.token", nil)
	options := UploadOptions{Protocol: "resources", Size: int64(len(data)), TransferID: "owned-upload", Metadata: map[string]string{"X-Upload-Batch-ID": "owned-batch"}}
	lease, err := b.UploadLease(sid, "", wire, options)
	if err != nil {
		t.Fatal(err)
	}
	other, _ := b.Open(source.URL, "owned.second.token", nil)
	res := uploadCall(t, "POST", lease, bytes.NewReader(data), map[string]string{"Content-Type": "application/octet-stream", "X-Auth": "foreign.token", "Authorization": "Bearer foreign", "Cookie": "foreign=1"})
	io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || calls.Load() != 1 {
		t.Fatal("upload acknowledgement failed", res.StatusCode, calls.Load())
	}
	stats, err := b.UploadStats(sid, lease)
	if err != nil || stats.SentBytes != int64(len(data)) || stats.AcceptedOffset != int64(len(data)) || stats.Active {
		t.Fatal(stats, err)
	}
	if _, err := b.UploadStats(other, lease); err == nil {
		t.Fatal("another account read upload statistics")
	}
	if got := uploadCall(t, "POST", lease, bytes.NewReader(data), nil).StatusCode; got != 409 {
		t.Fatal("completed upload was replayed", got)
	}
	read, _ := b.Lease(sid, "/api/raw/owned.bin", true)
	if got := uploadCall(t, "POST", read, strings.NewReader("forbidden"), nil).StatusCode; got != 405 {
		t.Fatal("read lease gained writes", got)
	}
	if calls.Load() != 1 {
		t.Fatal("unexpected write reached source")
	}
	b.CloseSession(sid)
	if got := uploadCall(t, "POST", lease, bytes.NewReader(data), nil).StatusCode; got != 410 {
		t.Fatal("closed account upload survived", got)
	}
}

func TestTusCreationOffsetResumeAndNativeHeadersUseExactFile(t *testing.T) {
	var data []byte
	var lock sync.Mutex
	var posts atomic.Int32
	wire := "/%D6%D0/owned%20%2B%25%23.bin"
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lock.Lock()
		defer lock.Unlock()
		if r.URL.EscapedPath() != "/nas/api/tus"+wire || r.Header.Get("X-Auth") != "owned.tus.token" || r.Header.Get("X-Transfer-ID") != "owned-tus" {
			t.Error("tus lost pinned path/ownership")
		}
		switch r.Method {
		case "POST":
			posts.Add(1)
			if r.Header.Get("Upload-Length") != "8" || r.ContentLength != 0 || r.URL.Query().Get("override") != "false" {
				t.Error("invalid creation")
			}
			w.Header().Set("Location", "/nas/api/tus"+wire+"?transfer=owned-tus")
			w.WriteHeader(201)
		case "HEAD":
			w.Header().Set("Upload-Length", "8")
			w.Header().Set("Upload-Offset", "4")
		case "PATCH":
			if r.Header.Get("Upload-Offset") != strconv.Itoa(len(data)) || r.Header.Get("Content-Type") != "application/offset+octet-stream" || r.URL.Query().Get("transfer") != "owned-tus" {
				t.Error("wrong upload offset or content type")
			}
			part, err := io.ReadAll(r.Body)
			if err != nil {
				t.Error(err)
			}
			data = append(data, part...)
			w.Header().Set("Upload-Offset", strconv.Itoa(len(data)))
			w.WriteHeader(204)
		default:
			t.Error("unexpected tus method", r.Method)
		}
	}))
	defer source.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(source.URL+"/nas", "owned.tus.token", nil)
	opts := UploadOptions{Protocol: "tus", Size: 8, TransferID: "owned-tus"}
	lease, err := b.UploadLease(sid, "", wire, opts)
	if err != nil {
		t.Fatal(err)
	}
	res := uploadCall(t, "POST", lease, nil, map[string]string{"Upload-Length": "8", "Tus-Resumable": "1.0.0"})
	if res.StatusCode != 201 || res.Header.Get("Location") != lease {
		t.Fatal("tus location was not mapped safely", res.StatusCode, res.Header.Get("Location"))
	}
	res.Body.Close()
	chunkHeaders := map[string]string{"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream", "Tus-Resumable": "1.0.0"}
	res = uploadCall(t, "PATCH", lease, strings.NewReader("abcd"), chunkHeaders)
	if res.StatusCode != 204 || res.Header.Get("Upload-Offset") != "4" {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	stats, _ := b.UploadStats(sid, lease)
	if stats.AcceptedOffset != 4 || stats.SentBytes != 4 {
		t.Fatal(stats)
	}
	res = uploadCall(t, "POST", lease, nil, map[string]string{"Upload-Length": "8"})
	if res.StatusCode != 409 {
		t.Fatal("duplicate creation reached source")
	}
	res.Body.Close()
	b.Revoke(lease)
	opts.Resume = true
	lease, err = b.UploadLease(sid, "", wire, opts)
	if err != nil {
		t.Fatal(err)
	}
	res = uploadCall(t, "HEAD", lease, nil, nil)
	if res.StatusCode != 200 || res.Header.Get("Upload-Length") != "8" || res.Header.Get("Upload-Offset") != "4" {
		t.Fatal("resume lost offset/length")
	}
	res.Body.Close()
	chunkHeaders["Upload-Offset"] = "4"
	chunkHeaders["X-HTTP-Method-Override"] = "PATCH"
	res = uploadCall(t, "POST", lease, strings.NewReader("efgh"), chunkHeaders)
	if res.StatusCode != 204 {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	lock.Lock()
	defer lock.Unlock()
	if string(data) != "abcdefgh" || posts.Load() != 1 {
		t.Fatal("resume repeated or lost original bytes", string(data), posts.Load())
	}
	stats, _ = b.UploadStats(sid, lease)
	if stats.AcceptedOffset != 8 || stats.SentBytes != 4 {
		t.Fatal(stats)
	}
}

func TestUploadUsesSelectedClientAndNeverFollowsRemoteRedirect(t *testing.T) {
	var dialed atomic.Int32
	var requests atomic.Int32
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Host != "only-embedded.invalid" || r.Header.Get("X-Auth") != "owned.network.token" {
			t.Error("selected network or account changed")
		}
		io.Copy(io.Discard, r.Body)
		w.Header().Set("Location", "http://foreign.invalid/api/resources/other")
		w.WriteHeader(307)
	}))
	defer source.Close()
	transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed.Add(1)
		if address != "only-embedded.invalid:80" {
			t.Error("redirect or unrelated source was dialed")
		}
		return (&net.Dialer{}).DialContext(ctx, network, source.Listener.Addr().String())
	}}
	defer transport.CloseIdleConnections()
	b, _ := New()
	defer b.Close()
	session, err := b.Open("http://only-embedded.invalid", "owned.network.token", &http.Client{Transport: transport})
	if err != nil {
		t.Fatal(err)
	}
	lease, err := b.UploadLease(session, "/owned.bin", "", UploadOptions{Protocol: "resources", Size: 1, TransferID: "owned-network"})
	if err != nil {
		t.Fatal(err)
	}
	response := uploadCall(t, "POST", lease, strings.NewReader("a"), nil)
	if response.StatusCode != 307 || response.Header.Get("Location") != "" || dialed.Load() != 1 || requests.Load() != 1 {
		t.Fatal("upload redirected or bypassed selected network", response.StatusCode, dialed.Load(), requests.Load())
	}
}

func TestInvalidTusAcknowledgementsCannotBecomeAcceptedProgress(t *testing.T) {
	for _, method := range []string{"HEAD", "PATCH"} {
		for _, ack := range []string{"", "-1", "9", "7"} {
			t.Run(method+ack, func(t *testing.T) {
				source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					io.Copy(io.Discard, r.Body)
					w.Header().Set("Upload-Length", "9")
					w.Header().Set("Upload-Offset", ack)
					if r.Method == "PATCH" {
						w.WriteHeader(204)
					}
				}))
				defer source.Close()
				b, _ := New()
				defer b.Close()
				sid, _ := b.Open(source.URL, "owned", nil)
				lease, _ := b.UploadLease(sid, "/owned.bin", "", UploadOptions{Protocol: "tus", Size: 8, TransferID: "owned-ack", Resume: true})
				var body io.Reader
				headers := map[string]string{}
				if method == "PATCH" {
					body = strings.NewReader("a")
					headers["Content-Type"] = "application/offset+octet-stream"
					headers["Upload-Offset"] = "0"
				}
				response := uploadCall(t, method, lease, body, headers)
				if response.StatusCode != 502 {
					t.Fatal("invalid acknowledgement accepted", response.StatusCode)
				}
				stats, _ := b.UploadStats(sid, lease)
				if stats.AcceptedOffset != -1 {
					t.Fatal(stats)
				}
			})
		}
	}
}

func TestUploadLocationRejectsOtherOriginsTargetsAndUnexpectedQuery(t *testing.T) {
	base, _ := BaseURL("https://owned.example/nas")
	current := "/api/tus/%D6%D0/file%252F%20%23.bin?override=false"
	for _, bad := range []string{"https://foreign.example/nas/api/tus/%D6%D0/file%252F%20%23.bin", "/nas/api/tus/other.bin", "/api/tus/%D6%D0/file%252F%20%23.bin", "/nas/api/tus/%D6%D0/file%252F%20%23.bin?transfer=other", "/nas/api/tus/%D6%D0/file%252F%20%23.bin?transfer=owned&transfer=owned", "/nas/api/tus/%D6%D0/file%252F%20%23.bin?token=secret", "", "http://u:p@owned.example/nas/api/tus/owned"} {
		if _, err := uploadLocation(base, current, bad, "owned"); err == nil {
			t.Fatal("unsafe Location accepted", bad)
		}
	}
	for _, location := range []string{"/nas/api/tus/%D6%D0/file%252F%20%23.bin", "https://owned.example/nas/api/tus/%D6%D0/file%252F%20%23.bin?transfer=owned"} {
		next, err := uploadLocation(base, current, location, "owned")
		if err != nil || next != "/api/tus/%D6%D0/file%252F%20%23.bin?transfer=owned" {
			t.Fatal("wire bytes changed", next, err)
		}
	}
}

func TestUploadBoundsAndMetadataDoNotReachUpstream(t *testing.T) {
	var calls atomic.Int32
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); t.Error("invalid upload reached upstream") }))
	defer source.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(source.URL, "owned", nil)
	for _, opts := range []UploadOptions{{Protocol: "unknown", Size: 1, TransferID: "owned"}, {Protocol: "tus", Size: -1, TransferID: "owned"}, {Protocol: "resources", Size: 1, TransferID: "\r\n"}, {Protocol: "resources", Size: 1, TransferID: "owned", Metadata: map[string]string{"X-Auth": "foreign"}}} {
		if _, err := b.UploadLease(sid, "/owned.bin", "", opts); err == nil {
			t.Fatal("bad options accepted")
		}
	}
	for _, wire := range []string{"/", "/folder/", "//foreign.test/owned", "/../owned", "/%2e%2e/owned"} {
		if _, err := b.UploadLease(sid, "", wire, UploadOptions{Protocol: "resources", Size: 1, TransferID: "owned"}); err == nil {
			t.Fatal("unsafe target accepted", wire)
		}
	}
	lease, _ := b.UploadLease(sid, "/owned.bin", "", UploadOptions{Protocol: "resources", Size: 1, TransferID: "owned"})
	res := uploadCall(t, "POST", lease, strings.NewReader("ab"), nil)
	if res.StatusCode != 413 {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	res = uploadCall(t, "PATCH", lease, strings.NewReader("a"), nil)
	if res.StatusCode != 405 {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	lease, _ = b.UploadLease(sid, "/owned.bin", "", UploadOptions{Protocol: "tus", Size: 8, TransferID: "owned", Resume: true})
	res = uploadCall(t, "PATCH", lease, strings.NewReader("ab"), map[string]string{"Content-Type": "application/offset+octet-stream", "Upload-Offset": "7"})
	if res.StatusCode != 413 {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	res = uploadCall(t, "PATCH", lease, strings.NewReader("a"), map[string]string{"Content-Type": "application/offset+octet-stream", "Upload-Offset": "-1"})
	if res.StatusCode != 400 {
		t.Fatal(res.StatusCode)
	}
	res.Body.Close()
	if calls.Load() != 0 {
		t.Fatal(calls.Load())
	}
}

func TestAcceptedUploadIsNotHiddenOrRepeatedWhenTokenRenewalFails(t *testing.T) {
	var posts atomic.Int32
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/renew" {
			w.WriteHeader(401)
			return
		}
		posts.Add(1)
		io.Copy(io.Discard, r.Body)
		w.Header().Set("X-Renew-Token", "true")
		w.Write([]byte("200 OK"))
	}))
	defer source.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(source.URL, "owned.token", nil)
	lease, _ := b.UploadLease(sid, "/owned.bin", "", UploadOptions{Protocol: "resources", Size: 1, TransferID: "owned"})
	res := uploadCall(t, "POST", lease, strings.NewReader("a"), nil)
	if res.StatusCode != 200 || res.Header.Get("X-Fileway-Session-Expired") != "true" {
		t.Fatal("accepted write was hidden", res.StatusCode)
	}
	res.Body.Close()
	if posts.Load() != 1 {
		t.Fatal("mutation repeated on renewal failure")
	}
}

func TestRevokeCancelsStalledUploadAndStatisticsRemainResponsive(t *testing.T) {
	started := make(chan struct{})
	cancelled := make(chan struct{})
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r.Body, buf); err != nil {
			t.Error(err)
			return
		}
		close(started)
		_, err := io.Copy(io.Discard, r.Body)
		if err == nil {
			t.Error("stalled upload was not cancelled")
		}
		close(cancelled)
	}))
	defer source.Close()
	b, _ := New()
	defer b.Close()
	sid, _ := b.Open(source.URL, "owned", nil)
	lease, _ := b.UploadLease(sid, "/owned.bin", "", UploadOptions{Protocol: "resources", Size: 1024 * 1024, TransferID: "owned"})
	reader, writer := io.Pipe()
	defer writer.Close()
	req, _ := http.NewRequest("POST", lease, reader)
	req.ContentLength = 1024 * 1024
	done := make(chan struct{})
	go func() {
		defer close(done)
		res, _ := http.DefaultClient.Do(req)
		if res != nil {
			res.Body.Close()
		}
	}()
	go func() { writer.Write([]byte("abcd")) }()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("upload did not start")
	}
	stats, err := b.UploadStats(sid, lease)
	if err != nil || !stats.Active || stats.SentBytes != 4 || stats.AcceptedOffset != -1 {
		t.Fatal(stats, err)
	}
	b.Revoke(lease)
	select {
	case <-cancelled:
	case <-time.After(3 * time.Second):
		t.Fatal("upstream upload survived revoke")
	}
	writer.CloseWithError(context.Canceled)
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("local upload stayed blocked")
	}
}
