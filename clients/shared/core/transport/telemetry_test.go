package transport

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

func TestLeaseStatsMeasuresResponseAndEnforcesOwner(t *testing.T) {
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Range", "bytes 10-15/100")
		w.Header().Set("Content-Length", "6")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "abcdef")
	}))
	defer source.Close()
	b, err := New()
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	owner, _ := b.Open(source.URL, "", nil)
	other, _ := b.Open(source.URL, "", nil)
	url, err := b.Lease(owner, "/api/raw/file", true)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	_, err = io.Copy(io.Discard, res.Body)
	res.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	stats, err := b.LeaseStats(owner, url)
	if err != nil {
		t.Fatal(err)
	}
	if stats.UpstreamBytes != 6 || stats.DeliveredBytes != 6 || stats.CurrentReadBytes != 6 || stats.CurrentReadTotal != 6 || stats.CacheReadBytes != 0 {
		t.Fatalf("response progress must not use total file length: %+v", stats)
	}
	if _, err := b.LeaseStats(other, url); err == nil {
		t.Fatal("another session read lease telemetry")
	}
	b.Revoke(url)
	if _, err := b.LeaseStats(owner, url); err == nil {
		t.Fatal("revoked telemetry remained readable")
	}
}

func TestLeaseStatsLateResponseCannotOverwriteCurrentRead(t *testing.T) {
	var stats leaseTelemetry
	old := stats.begin()
	stats.total(old, 100)
	current := stats.begin()
	stats.total(current, 20)
	var workers sync.WaitGroup
	for i := 0; i < 10; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); stats.add(10, 0, 10, old) }()
	}
	stats.add(0, 20, 20, current)
	workers.Wait()
	stats.finish(old)
	got := stats.snapshot()
	if got.CurrentReadBytes != 20 || got.CurrentReadTotal != 20 || !got.Active || got.UpstreamBytes != 100 || got.CacheReadBytes != 20 || got.DeliveredBytes != 120 {
		t.Fatalf("late requests or cache reads corrupted current/network progress: %+v", got)
	}
}

func TestLeaseStatsDoesNotCountLocalErrorAsMedia(t *testing.T) {
	var stats leaseTelemetry
	id := stats.begin()
	stats.total(id, 100)
	w := &measuredWriter{ResponseWriter: httptest.NewRecorder(), stats: &stats, request: id}
	http.Error(w, "source unavailable", http.StatusBadGateway)
	got := stats.snapshot()
	if got.DeliveredBytes != 0 || got.CurrentReadBytes != 0 || got.CurrentReadTotal != -1 {
		t.Fatalf("error page counted as media progress: %+v", got)
	}
}
