package fbhttp

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"reflect"
	"strings"
	"testing"
)

func TestVideoPreviewCandidatesFollowDurationPolicy(t *testing.T) {
	tests := []struct {
		name     string
		duration float64
		want     []float64
	}{
		{name: "short", duration: 4.9, want: []float64{1}},
		{name: "five seconds", duration: 5, want: []float64{5}},
		{name: "one hour", duration: 3600, want: []float64{5}},
		{name: "feature film", duration: 3600.1, want: []float64{60, 120}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := videoPreviewCandidates(test.duration); !reflect.DeepEqual(got, test.want) {
				t.Fatalf("candidates(%v) = %v, want %v", test.duration, got, test.want)
			}
		})
	}
}

func TestNormalizeVideoPreviewTimeAvoidsEndOfFile(t *testing.T) {
	if got := normalizeVideoPreviewTime(2, 1.5); got != 1.4 {
		t.Fatalf("normalized timestamp = %v, want 1.4", got)
	}
	if got := normalizeVideoPreviewTime(1, 0.5); got != 0.4 {
		t.Fatalf("short normalized timestamp = %v, want 0.4", got)
	}
}

func TestSelectVideoPreviewSkipsBlackFramesAndKeepsBrightestFallback(t *testing.T) {
	black := encodePreviewColor(t, color.RGBA{A: 255})
	dim := encodePreviewColor(t, color.RGBA{R: 8, G: 8, B: 8, A: 255})
	bright := encodePreviewColor(t, color.RGBA{R: 240, G: 180, B: 80, A: 255})

	selected, ok := selectVideoPreview([][]byte{black, dim, bright})
	if !ok || !bytes.Equal(selected, bright) {
		t.Fatal("bright non-black candidate should win after black frames")
	}

	selected, ok = selectVideoPreview([][]byte{dim, black})
	if !ok || !bytes.Equal(selected, dim) {
		t.Fatal("brightest decodable black candidate should be retained as fallback")
	}
	if _, ok := selectVideoPreview([][]byte{[]byte("corrupt")}); ok {
		t.Fatal("undecodable candidates must not be selected")
	}
}

func TestVideoProbePrefersAttachedPictureStream(t *testing.T) {
	var document videoProbeDocument
	document.Streams = []videoProbeStream{
		{Index: 0, CodecType: "video", CodecName: "h264"},
		{Index: 1, CodecType: "video", CodecName: "mjpeg", Disposition: struct {
			AttachedPic int `json:"attached_pic"`
		}{AttachedPic: 1}},
	}
	attached := document.attachedPictureStreams()
	if len(attached) != 1 || attached[0].Index != 1 {
		t.Fatalf("attached streams = %+v", attached)
	}
	primary, ok := document.primaryVideoStream()
	if !ok || primary.Index != 0 {
		t.Fatalf("primary stream = %+v, ok=%t", primary, ok)
	}
}

func TestVideoPreviewFilterUsesThumbnailAndHDRToneMapping(t *testing.T) {
	standard := videoPreviewFilter(false, true)
	if !strings.Contains(standard, "thumbnail=n=48") || !strings.Contains(standard, "scale=256:256") {
		t.Fatalf("standard filter = %q", standard)
	}
	hdr := videoPreviewFilter(true, true)
	for _, part := range []string{"zscale=", "tonemap=", "bt709", "thumbnail=n=48"} {
		if !strings.Contains(hdr, part) {
			t.Fatalf("HDR filter %q missing %q", hdr, part)
		}
	}
}

func TestFFmpegPreviewArgsUseInputSideSeekAndStreamMapping(t *testing.T) {
	at := 5.0
	args := ffmpegPreviewArgs("/movie/demo.mkv", 3, &at, "thumbnail=n=48")
	joined := strings.Join(args, "\x00")
	if !strings.Contains(joined, "-ss\x005.000\x00-i\x00/movie/demo.mkv") {
		t.Fatalf("input-side seek missing from args: %q", joined)
	}
	if !strings.Contains(joined, "-map\x000:3") {
		t.Fatalf("absolute stream mapping missing from args: %q", joined)
	}
}

func encodePreviewColor(t *testing.T, value color.RGBA) []byte {
	t.Helper()
	imageValue := image.NewRGBA(image.Rect(0, 0, 8, 8))
	for y := 0; y < imageValue.Bounds().Dy(); y++ {
		for x := 0; x < imageValue.Bounds().Dx(); x++ {
			imageValue.SetRGBA(x, y, value)
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, imageValue, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}
