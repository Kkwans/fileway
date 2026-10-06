package fbhttp

import (
	"bytes"
	"context"
	"image/jpeg"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestSpriteTileGeometryPreservesSourceAspectInsideBounds(t *testing.T) {
	tests := []struct {
		name                                 string
		width, height, wantWidth, wantHeight int
	}{
		{"landscape", 1920, 1080, 160, 90},
		{"portrait", 1080, 1920, 50, 90},
		{"square", 1000, 1000, 90, 90},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			width, height := spriteTileGeometry(test.width, test.height)
			if width != test.wantWidth || height != test.wantHeight {
				t.Fatalf("geometry = %dx%d, want %dx%d", width, height, test.wantWidth, test.wantHeight)
			}
		})
	}
}

func TestSpriteConcurrentFramesKeepTheirSourceTimeOrder(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("FFmpeg unavailable")
	}
	source := filepath.Join(t.TempDir(), "two-colors.webm")
	command := exec.Command(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=1:d=1", "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=1:d=1", "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-threads", "1", "-g", "1", source)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("fixture: %s %v", output, err)
	}
	data, err := generateVideoSprite(context.Background(), source, videoSpriteResponse{Number: 2, Column: 2, Width: 160, Height: 90, Interval: 1})
	if err != nil {
		t.Fatal(err)
	}
	sheet, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	r0, _, b0, _ := sheet.At(80, 45).RGBA()
	r1, _, b1, _ := sheet.At(240, 45).RGBA()
	if r0 <= b0+20000 || b1 <= r1+20000 {
		t.Fatalf("frames reordered or missing: red=%d/%d blue=%d/%d", r0, b0, r1, b1)
	}
}

func TestSpriteSamplingIsBounded(t *testing.T) {
	interval, number := spriteSampling(3600)
	if interval != 36 || number != 100 {
		t.Fatalf("long sampling = %.2f/%d", interval, number)
	}
	interval, number = spriteSampling(12)
	if interval != 1 || number != 12 {
		t.Fatalf("short sampling = %.2f/%d", interval, number)
	}
}

func TestSpriteSamplesFullMovieAndSeeksBeforeDecoding(t *testing.T) {
	interval, number := spriteSampling(10800)
	if interval != 108 || number != 100 {
		t.Fatalf("%v %v", interval, number)
	}
	args := strings.Join(spriteFrameArgs("film.mkv", 10692, 160, 66), " ")
	if !strings.Contains(args, "-ss 10692.000000 -i film.mkv") || !strings.Contains(args, "-frames:v 1") {
		t.Fatal(args)
	}
}
