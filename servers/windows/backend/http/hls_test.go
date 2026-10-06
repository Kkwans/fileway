package fbhttp

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/hls"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMediaHLSHTTPIsExplicitMergedPrivateAndServesAssets(t *testing.T) {
	h := newTrashHTTPHarness(t,
		users.User{Username: "owner", Perm: users.Permissions{Download: true}},
		users.User{Username: "other", Perm: users.Permissions{Download: true}},
		users.User{Username: "blocked"},
	)
	owner := trashHTTPUserByName(t, h, "owner")
	other := trashHTTPUserByName(t, h, "other")
	blocked := trashHTTPUserByName(t, h, "blocked")
	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("video fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(h.fs[other.ID], "/film.mp4", []byte("other video"), 0o600); err != nil {
		t.Fatal(err)
	}
	service := newHTTPHLSService(t, false)
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}

	response := h.request(t, blocked.ID, mediaHLSStartHandler(service, runtime), http.MethodPost, "/media/hls", bytes.NewBufferString(`{"path":"/film.mp4"}`), nil)
	if response.Code != http.StatusForbidden {
		t.Fatalf("blocked start status = %d body=%s", response.Code, response.Body.String())
	}
	response = h.request(t, owner.ID, mediaHLSStartHandler(service, runtime), http.MethodPost, "/media/hls", bytes.NewBufferString(`{"path":"/film.mp4"}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("start status = %d body=%s", response.Code, response.Body.String())
	}
	var started mediaHLSResponse
	if err := json.Unmarshal(response.Body.Bytes(), &started); err != nil {
		t.Fatal(err)
	}
	if started.ID == "" || started.TaskID == "" || started.State != hls.StateQueued || started.PlaylistURL != "" {
		t.Fatalf("started = %#v", started)
	}

	merged := h.request(t, owner.ID, mediaHLSStartHandler(service, runtime), http.MethodPost, "/media/hls", bytes.NewBufferString(`{"path":"/film.mp4"}`), nil)
	if merged.Code != http.StatusOK || !strings.Contains(merged.Body.String(), started.ID) {
		t.Fatalf("merged status = %d body=%s", merged.Code, merged.Body.String())
	}
	waitForHTTPTask(t, h, started.TaskID, tasks.StatusCompleted)

	statusResponse := h.request(t, owner.ID, mediaHLSGetHandler(service), http.MethodGet, "/media/hls/"+started.ID, nil, map[string]string{"id": started.ID})
	if statusResponse.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", statusResponse.Code, statusResponse.Body.String())
	}
	var completed mediaHLSResponse
	if err := json.Unmarshal(statusResponse.Body.Bytes(), &completed); err != nil {
		t.Fatal(err)
	}
	if completed.State != hls.StateCompleted || !strings.HasSuffix(completed.PlaylistURL, "/api/media/hls/"+started.ID+"/index.m3u8") {
		t.Fatalf("completed = %#v", completed)
	}

	playlist := h.request(t, owner.ID, mediaHLSAssetHandler(service), http.MethodGet, completed.PlaylistURL, nil, map[string]string{"id": started.ID, "asset": "index.m3u8"})
	if playlist.Code != http.StatusOK || !strings.Contains(playlist.Body.String(), "segment-000000.ts") {
		t.Fatalf("playlist = %d body=%s", playlist.Code, playlist.Body.String())
	}
	if !strings.Contains(playlist.Header().Get("Content-Type"), "mpegurl") || playlist.Header().Get("Cache-Control") != "private, no-cache" {
		t.Fatalf("playlist headers = %#v", playlist.Header())
	}
	segment := h.request(t, owner.ID, mediaHLSAssetHandler(service), http.MethodGet, "/media/hls/"+started.ID+"/segment-000000.ts", nil, map[string]string{"id": started.ID, "asset": "segment-000000.ts"})
	if segment.Code != http.StatusOK || segment.Body.String() != "segment-data" || !strings.Contains(segment.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("segment = %d headers=%#v body=%s", segment.Code, segment.Header(), segment.Body.String())
	}
	crossUser := h.request(t, other.ID, mediaHLSGetHandler(service), http.MethodGet, "/media/hls/"+started.ID, nil, map[string]string{"id": started.ID})
	if crossUser.Code != http.StatusForbidden {
		t.Fatalf("cross-user status = %d body=%s", crossUser.Code, crossUser.Body.String())
	}
}

func TestMediaHLSCancelDoesNotOfferRetry(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/slow.mp4", []byte("video fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	service := newHTTPHLSService(t, true)
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	response := h.request(t, owner.ID, mediaHLSStartHandler(service, runtime), http.MethodPost, "/media/hls", bytes.NewBufferString(`{"path":"/slow.mp4"}`), nil)
	if response.Code != http.StatusAccepted {
		t.Fatalf("start status = %d body=%s", response.Code, response.Body.String())
	}
	var started mediaHLSResponse
	if err := json.Unmarshal(response.Body.Bytes(), &started); err != nil {
		t.Fatal(err)
	}
	waitForHLSHTTPState(t, service, started.ID, owner.ID, hls.StateStreamable)
	canceled := h.request(t, owner.ID, mediaHLSCancelHandler(service, runtime), http.MethodPost, "/media/hls/"+started.ID+"/cancel", nil, map[string]string{"id": started.ID})
	if canceled.Code != http.StatusAccepted {
		t.Fatalf("cancel status = %d body=%s", canceled.Code, canceled.Body.String())
	}
	waitForHTTPTask(t, h, started.TaskID, tasks.StatusCanceled)
	waitForHLSHTTPState(t, service, started.ID, owner.ID, hls.StateCanceled)

	retryResponse := h.request(t, owner.ID, taskRetryHandler(runtime, service), http.MethodPost, "/tasks/"+started.TaskID+"/retry", nil, map[string]string{"id": started.TaskID})
	if retryResponse.Code != http.StatusConflict {
		t.Fatalf("retry status = %d body=%s", retryResponse.Code, retryResponse.Body.String())
	}
}

func TestMediaHLSFormatUsesRemuxForCompatibleStreams(t *testing.T) {
	if got := mediaHLSFormatForInput(hls.Input{VideoCodec: "h264", AudioCodec: "aac"}); got != "copy" {
		t.Fatalf("compatible streams format = %q, want copy", got)
	}
	if got := mediaHLSFormatForInput(hls.Input{
		VideoCodec: "h264", AudioCodec: "aac", VideoPixelFormat: "yuv420p10le",
		VideoProfile: "High 10", VideoBitDepth: 10,
	}); got != "hls" {
		t.Fatalf("ten-bit H.264 format = %q, want hls", got)
	}
	if got := mediaHLSFormatForInput(hls.Input{VideoCodec: "hevc", AudioCodec: "aac"}); got != "hls" {
		t.Fatalf("HEVC streams format = %q, want hls", got)
	}
}

func TestMediaHLSReserveForFormatPreservesExplicitArtifactType(t *testing.T) {
	service := newHTTPHLSService(t, false)
	input := hls.Input{
		UserID: 1, Path: "/movie.mkv", Identity: "v1", SourcePath: "/source.mkv",
	}
	wantProfiles := map[string]string{
		"hls":       hls.DefaultProfile,
		"copy":      hls.DefaultCopyProfile,
		"mp4-copy":  hls.DefaultMP4CopyProfile,
		"webm":      hls.DefaultWebMProfile,
		"webm-copy": hls.DefaultWebMCopyProfile,
	}
	for format, wantProfile := range wantProfiles {
		t.Run(format, func(t *testing.T) {
			var gotProfile string
			status, created, err := reserveHLSForFormat(service, format)(input, func(job hls.Job) (string, error) {
				gotProfile = job.Profile
				return "task-" + format, nil
			})
			if err != nil || !created || status.Profile != wantProfile || gotProfile != wantProfile {
				t.Fatalf("format=%q status=%#v created=%v profile=%q want=%q err=%v", format, status, created, gotProfile, wantProfile, err)
			}
		})
	}
}

func TestMediaHLSStatusResponseMarksRemuxProfile(t *testing.T) {
	response := mediaHLSStatusResponse("", hls.Status{
		ID: "copy-id", UserID: 1, Path: "/movie.mkv", Identity: "v1",
		Profile: hls.DefaultCopyProfile, State: hls.StateCompleted, ProcessedSeconds: 12.5, DurationSeconds: 20.042,
	})
	if response.Format != "copy" || response.PlaylistURL == "" || response.ProcessedSeconds != 12.5 || response.DurationSeconds != 20.042 {
		t.Fatalf("copy response = %#v", response)
	}
}

func TestMediaHLSStatusResponseMarksWebMRemuxProfile(t *testing.T) {
	response := mediaHLSStatusResponse("", hls.Status{
		ID: "webm-copy-id", UserID: 1, Path: "/movie.mkv", Identity: "v1",
		Profile: hls.DefaultWebMCopyProfile, State: hls.StateCompleted,
	})
	if response.Format != "webm-copy" || response.SourceURL == "" || response.PlaylistURL != "" {
		t.Fatalf("WebM copy response = %#v", response)
	}
}

func TestMediaHLSStatusResponseMarksMP4RemuxProfile(t *testing.T) {
	response := mediaHLSStatusResponse("", hls.Status{
		ID: "mp4-copy-id", UserID: 1, Path: "/movie.mkv", Identity: "v1",
		Profile: hls.DefaultMP4CopyProfile, State: hls.StateCompleted,
	})
	if response.Format != "mp4-copy" || response.SourceURL == "" || response.PlaylistURL != "" {
		t.Fatalf("MP4 copy response = %#v", response)
	}
}

func waitForHLSHTTPState(t *testing.T, service *hls.Service, id string, userID uint, expected hls.State) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status, err := service.Get(id, userID)
		if err == nil && status.State == expected {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	status, err := service.Get(id, userID)
	if errors.Is(err, hls.ErrNotFound) {
		t.Fatalf("HLS status disappeared")
	}
	t.Fatalf("HLS state = %#v, %v; want %s", status, err, expected)
}
