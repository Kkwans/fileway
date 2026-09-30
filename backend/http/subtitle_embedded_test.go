package fbhttp

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestValidEmbeddedTextSubtitleStream(t *testing.T) {
	tracks := []mediaTrack{
		{Index: 3, Codec: "hdmv_pgs_subtitle"},
		{Index: 4, Codec: "subrip"},
		{Index: 5, Codec: "ass"},
		{Index: 6, Codec: "mov_text"},
	}
	for _, index := range []int{4, 5, 6} {
		if !validEmbeddedTextSubtitleStream(tracks, index) {
			t.Fatalf("text subtitle stream %d was rejected", index)
		}
	}
	for _, index := range []int{2, 3, 7} {
		if validEmbeddedTextSubtitleStream(tracks, index) {
			t.Fatalf("unsupported subtitle stream %d was accepted", index)
		}
	}
}

func TestExtractEmbeddedSubtitleVTTSelectsASSStream(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("FFmpeg is not installed")
	}
	directory := t.TempDir()
	first := filepath.Join(directory, "first.srt")
	second := filepath.Join(directory, "second.srt")
	for path, content := range map[string]string{
		first:  "1\n00:00:00,200 --> 00:00:02,200\nEnglish track\n",
		second: "1\n00:00:00,200 --> 00:00:02,200\n中文字幕测试\n",
	} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	source := filepath.Join(directory, "测试 文本字幕.mkv")
	command := exec.Command(ffmpeg,
		"-hide_banner", "-loglevel", "error", "-nostdin",
		"-f", "lavfi", "-i", "color=c=black:s=320x180:r=5:d=3",
		"-i", first, "-i", second,
		"-map", "0:v:0", "-map", "1:0", "-map", "2:0",
		"-c:v", "mpeg4", "-c:s:0", "srt", "-c:s:1", "ass", source,
	)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("create text subtitle fixture: %v: %s", err, output)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	content, err := extractEmbeddedSubtitleVTT(ctx, source, 2)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(content), "WEBVTT") ||
		!strings.Contains(string(content), "中文字幕测试") ||
		strings.Contains(string(content), "English track") {
		t.Fatalf("wrong embedded subtitle extracted: %q", content)
	}
}
