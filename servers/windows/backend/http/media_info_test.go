package fbhttp

import (
	"context"
	"net/http"
	"strings"
	"testing"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/users"
)

func TestMediaInfoRequiresExplicitLocationRequest(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/film.mp4", []byte("video"), 0o600); err != nil {
		t.Fatal(err)
	}
	requests := make([]bool, 0, 2)
	probe := func(_ context.Context, _ string, includeLocation bool) (mediaProbeResult, error) {
		requests = append(requests, includeLocation)
		return mediaProbeResult{
			Format: "mov,mp4", Duration: 12.5, BitRate: 1200,
			VideoCodec: "h264", AudioCodec: "aac", Width: 1920, Height: 1080,
			Location: "+31.2304+121.4737/",
		}, nil
	}

	response := h.request(t, owner.ID, mediaInfoHandler(probe), http.MethodGet, "/media/info?path=/film.mp4", nil, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("basic status = %d body=%s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "location") || strings.Contains(response.Body.String(), "31.2304") {
		t.Fatalf("basic response leaked location: %s", response.Body.String())
	}
	response = h.request(t, owner.ID, mediaInfoHandler(probe), http.MethodGet, "/media/info?path=/film.mp4&includeLocation=true", nil, nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "+31.2304+121.4737/") {
		t.Fatalf("location status = %d body=%s", response.Code, response.Body.String())
	}
	if len(requests) != 2 || requests[0] || !requests[1] {
		t.Fatalf("includeLocation calls = %#v", requests)
	}
}

func TestMediaInfoKeepsBaseFieldsWhenProbeFails(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true}})
	owner := firstTrashHTTPUser(h)
	if err := afero.WriteFile(h.fs[owner.ID], "/song.mp3", []byte("audio"), 0o600); err != nil {
		t.Fatal(err)
	}
	probe := func(context.Context, string, bool) (mediaProbeResult, error) {
		return mediaProbeResult{}, context.DeadlineExceeded
	}
	response := h.request(t, owner.ID, mediaInfoHandler(probe), http.MethodGet, "/media/info?path=/song.mp3", nil, nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"name":"song.mp3"`) || !strings.Contains(response.Body.String(), "technicalError") {
		t.Fatalf("fallback status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestSummarizeFFprobeKeepsLocationOptIn(t *testing.T) {
	document := ffprobeDocument{}
	document.Format.FormatName = "matroska,webm"
	document.Format.Duration = "65.25"
	document.Format.BitRate = "9000"
	document.Format.Tags = map[string]string{
		"TITLE": "Demo", "location-eng": "+10.0+20.0/",
	}
	document.Streams = append(document.Streams, ffprobeStream{
		CodecType: "video", CodecName: "hevc", Width: 3840, Height: 2160,
		PixelFormat: "yuv420p10le", Profile: "Main 10", BitsPerRawSample: "10",
	})

	without := summarizeFFprobe(document, false)
	with := summarizeFFprobe(document, true)
	if without.Location != "" || with.Location != "+10.0+20.0/" {
		t.Fatalf("locations = %q / %q", without.Location, with.Location)
	}
	if with.VideoCodec != "hevc" || with.Width != 3840 || with.Duration != 65.25 {
		t.Fatalf("summary = %#v", with)
	}
	if with.VideoPixelFormat != "yuv420p10le" || with.VideoProfile != "Main 10" || with.VideoBitDepth != 10 {
		t.Fatalf("video compatibility details = %#v", with)
	}
}
