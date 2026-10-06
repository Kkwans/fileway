package files

import (
	"testing"

	"github.com/spf13/afero"
)

func TestNewFileInfoCanSkipSubtitleDirectoryScan(t *testing.T) {
	t.Parallel()

	fs := afero.NewMemMapFs()
	if err := fs.MkdirAll("/movies", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(fs, "/movies/title.mp4", []byte("video"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(fs, "/movies/title.srt", []byte("1\n00:00:00,000 --> 00:00:01,000\n字幕"), 0o644); err != nil {
		t.Fatal(err)
	}

	withSubtitles, err := NewFileInfo(&FileOptions{
		Fs:      fs,
		Path:    "/movies/title.mp4",
		Expand:  true,
		Checker: allowAllRiskTestPaths{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if withSubtitles.Type != "video" || len(withSubtitles.Subtitles) != 1 {
		t.Fatalf("default video metadata = type %q, subtitles %d; want video and one subtitle", withSubtitles.Type, len(withSubtitles.Subtitles))
	}

	withoutSubtitles, err := NewFileInfo(&FileOptions{
		Fs:            fs,
		Path:          "/movies/title.mp4",
		Expand:        true,
		SkipSubtitles: true,
		Checker:       allowAllRiskTestPaths{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if withoutSubtitles.Type != "video" {
		t.Fatalf("skipped video metadata type = %q, want video", withoutSubtitles.Type)
	}
	if len(withoutSubtitles.Subtitles) != 0 {
		t.Fatalf("skipped subtitle metadata = %d, want zero", len(withoutSubtitles.Subtitles))
	}
}
