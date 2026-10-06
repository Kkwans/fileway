package hls

import (
	"strings"
	"testing"
)

func TestAudioOnlyConversionPreservesVideoAndSelectedTrack(t *testing.T) {
	index := 3
	args := copyTrackArgs(mp4CopyArgs("source.mkv", "output.mp4"), Job{Profile: DefaultMP4AudioProfile, AudioStream: &index})
	joined := strings.Join(args, " ")
	for _, want := range []string{"-map 0:3", "-c:v copy", "-c:a aac", "-movflags +faststart"} {
		if !strings.Contains(joined, want) {
			t.Fatal(joined)
		}
	}
	if args[len(args)-1] != "output.mp4" || strings.Contains(joined, "libx264") || strings.Contains(joined, "-vf") {
		t.Fatal(joined)
	}
}

func TestExplicitQualityPreservesOnlySourcesWithinLimits(t *testing.T) {
	for _, tc := range []struct {
		q    string
		w, h int
		want bool
	}{
		{"source", 3840, 1600, true}, {"4k", 3840, 1600, true}, {"1080p", 3840, 1600, false}, {"1080p", 1920, 800, true}, {"1080p", 0, 0, false}, {"source", 0, 0, true},
	} {
		if got := QualityPreservesSource(tc.q, tc.w, tc.h); got != tc.want {
			t.Fatalf("%+v: %v", tc, got)
		}
	}
}
