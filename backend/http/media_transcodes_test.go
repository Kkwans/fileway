package fbhttp

import (
	"bytes"
	"context"
	"net/http"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/settings"
	"github.com/Kkwans/nas-file-browser/backend/tasks"
	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestTranscodesRequireReadableVideosAndWritableDestination(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true, Create: true}}, users.User{Username: "blocked", Perm: users.Permissions{Download: true}})
	owner := trashHTTPUserByName(t, h, "owner")
	blocked := trashHTTPUserByName(t, h, "blocked")
	service := newHTTPHLSService(t, false)
	runtime, err := tasks.NewRuntime(h.storage.Tasks)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		user   uint
		body   string
		status int
	}{
		{blocked.ID, `{"paths":["/film.mkv"],"destination":"/"}`, http.StatusForbidden},
		{owner.ID, `{"paths":[],"destination":"/"}`, http.StatusBadRequest},
		{owner.ID, `{"paths":["/film.mkv"],"quality":"bogus","destination":"/"}`, http.StatusBadRequest},
	} {
		r := h.request(t, tc.user, mediaTranscodesHandler(service, runtime), http.MethodPost, "/media/transcodes", bytes.NewBufferString(tc.body), nil)
		if r.Code != tc.status {
			t.Fatalf("%s: %d %s", tc.body, r.Code, r.Body.String())
		}
	}
}

func TestTranscodeDirectoryScanDeduplicatesAndSkipsNonVideo(t *testing.T) {
	h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: true, Create: true}})
	owner := firstTrashHTTPUser(h)
	fs := h.fs[owner.ID]
	if err := fs.MkdirAll("/movies/nested", 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"/movies/film.mkv", "/movies/nested/film.mp4", "/movies/readme.txt"} {
		if err := afero.WriteFile(fs, name, []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	owner.Fs = fs
	d := &data{user: owner, server: h.server, store: h.storage, settings: &settings.Settings{}}
	videos, err := collectTranscodeVideos(context.Background(), d, []string{"/movies", "/movies/film.mkv"})
	if err != nil {
		t.Fatal(err)
	}
	if len(videos) != 2 {
		t.Fatal(videos)
	}
}
