package transport

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestOnlyExplicitResourceChecksumUsesLongDeadline(t *testing.T) {
	for _, endpoint := range []string{"/api/resources/owned?checksum=sha256", "/api/resources/%FF?checksum=md5"} {
		if RequestTimeout("GET", endpoint) != 15*time.Minute {
			t.Fatal(endpoint)
		}
	}
	for _, endpoint := range []string{"/api/resources/a", "/api/resources/a?checksum=invalid", "/api/settings?checksum=sha256", "https://example.com/api/resources/a?checksum=sha256"} {
		if RequestTimeout("GET", endpoint) != 30*time.Second {
			t.Fatal(endpoint)
		}
	}
	if RequestTimeout("POST", "/api/resources/a?checksum=sha256") != 30*time.Second {
		t.Fatal("write got long deadline")
	}
}

func TestChecksumUsesSelectedTransportAndRetainsCancellation(t *testing.T) {
	entered := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Auth") != "owned-token" {
			t.Error("lost session auth")
		}
		entered <- struct{}{}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(25 * time.Millisecond):
			w.Write([]byte(`{"checksums":{"sha256":"owned"}}`))
		}
	}))
	defer server.Close()
	client := DirectClient()
	selected := client.Transport.(*http.Transport)
	selected.ResponseHeaderTimeout = time.Millisecond
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	id, err := b.Open(server.URL, "owned-token", client)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	response, err := b.Request(ctx, id, "GET", "/api/resources/owned?checksum=sha256", nil)
	if err != nil || response.Status != 200 {
		t.Fatalf("long headers rejected: %#v %v", response, err)
	}
	if selected.ResponseHeaderTimeout != time.Millisecond {
		t.Fatal("mutated selected transport")
	}
	<-entered
	ctx2, stop := context.WithCancel(context.Background())
	finished := make(chan error, 1)
	go func() {
		_, err := b.Request(ctx2, id, "GET", "/api/resources/owned?checksum=sha256", nil)
		finished <- err
	}()
	<-entered
	stop()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancelled request succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not stop request")
	}
}
