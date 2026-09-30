package fbhttp

import (
	"github.com/Kkwans/nas-file-browser/backend/hls"
	"math"
	"testing"
)

func TestPlaybackWindowRequiresKnownBoundedSource(t *testing.T) {
	for _, tc := range []struct {
		duration, start, length float64
		valid                   bool
	}{
		{600, 300, 120, true}, {12, 10, 16, true}, {0, 0, 16, false}, {600, 600, 16, false}, {600, -1, 16, false}, {600, 0, 121, false}, {600, 0, 1, false}, {600, 10, 0, false}, {600, 0, 0, true}, {600, math.NaN(), 16, false},
	} {
		input := hls.Input{DurationSeconds: tc.duration}
		err := applyPlaybackWindow(&input, tc.start, tc.length, "session")
		if (err == nil) != tc.valid {
			t.Fatalf("%+v: %v", tc, err)
		}
	}
}
