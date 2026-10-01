package hls

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestRKMPPArgumentsPreserveTracksGeometryAndCopyPaths(t *testing.T) {
	subtitle, audio := 5, 3
	job := Job{Profile: ProfileForQuality("1080p", 1600), VideoCodec: "hevc", VideoWidth: 3840, VideoHeight: 1600, HDR: true, SubtitleStream: &subtitle, AudioStream: &audio}
	args, method := acceleratorArgs(append([]string{"ffmpeg"}, ffmpegTrackArgs("source", "segments", "playlist", 1920, 1080, &subtitle, &audio, true)...), job, "rkmpp")
	joined := strings.Join(args, " ")
	for _, want := range []string{"-hwaccel rkmpp", "scale_rkrga=w=1920:h=800:format=p010le", "tonemap_opencl", "[0:5]scale=", "hwupload=derive_device=rkmpp", "overlay_rkrga=shortest=1", "-map 0:3", "-c:v h264_rkmpp", "-rc_mode CQP", "-color_trc bt709"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %s: %s", want, joined)
		}
	}
	if method != "hardware" || strings.Contains(joined, "libx264") || strings.Contains(joined, "-preset") {
		t.Fatal(joined, method)
	}
	job.Profile = DefaultMP4CopyProfile
	copyArgs := append([]string{"ffmpeg"}, mp4CopyArgs("source", "output")...)
	if got, method := acceleratorArgs(copyArgs, job, "rkmpp"); method != "remux" || !reflect.DeepEqual(got, copyArgs) {
		t.Fatal(got, method)
	}
	job.Profile = DefaultProfile
	job.VideoWidth = 8192
	if _, method := acceleratorArgs(args, job, "rkmpp"); method != "software" {
		t.Fatal("oversized sources must use software")
	}
}

func TestHDRWebMWithoutPGSDownloadsOpenCLFramesDirectly(t *testing.T) {
	job := Job{Profile: DefaultWebMProfile, VideoCodec: "hevc", VideoWidth: 3840, VideoHeight: 1600, HDR: true}
	args, method := acceleratorArgs(append([]string{"ffmpeg"}, webMTrackArgs("source", "output", 1280, 720, nil, nil, true)...), job, "rkmpp")
	joined := strings.Join(args, " ")
	if method != "hybrid" || !strings.Contains(joined, "tonemap_opencl=tonemap=hable:format=nv12,hwdownload,format=nv12,format=yuv420p") || strings.Contains(joined, "derive_device=rkmpp:reverse=1") {
		t.Fatal(joined, method)
	}
}

// This gate is also run in the isolated RK3588 release container with a real
// HDR/PGS source. It never reads or writes production task/database state.
func TestRKMPPRealHDRExportAndSubtitle(t *testing.T) {
	source := os.Getenv("NFB_RKMPP_SOURCE")
	if source == "" {
		t.Skip("requires isolated RK3588 media fixture")
	}
	root := t.TempDir()
	if output := os.Getenv("NFB_RKMPP_OUTPUT_DIR"); output != "" {
		var err error
		root, err = os.MkdirTemp(output, "rk-gate-")
		if err != nil {
			t.Fatal(err)
		}
	}
	service, err := New(Config{CacheDir: filepath.Join(root, "cache"), Workers: 1, Accelerator: "rkmpp"})
	if err != nil {
		t.Fatal(err)
	}
	for _, subtitles := range []bool{false, true} {
		t.Run(fmt.Sprint("subtitles-", subtitles), func(t *testing.T) {
			job := Job{UserID: 1, Path: "/fixture.mkv", SourcePath: source, Profile: ProfileForQuality("1080p", 1600), DurationSeconds: 12.47, VideoCodec: "hevc", VideoWidth: 3840, VideoHeight: 1600, HDR: true}
			if subtitles {
				index := 2
				job.SubtitleStream = &index
			}
			output := filepath.Join(root, fmt.Sprintf("export-%t.mp4", subtitles))
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			started := time.Now()
			live := false
			err := service.Export(ctx, func() (Job, error) { return job, nil }, output, fmt.Sprint(subtitles), func(p Progress) error {
				if p.Method != "" && p.Method != "hardware" {
					t.Errorf("incorrect method: %+v", p)
				}
				if p.Phase == "encoding" && p.PlayableSeconds > 0 {
					live = true
				}
				return nil
			})
			if err != nil {
				t.Fatal(err)
			}
			if !live {
				t.Fatal("no live segment before finalization")
			}
			probe, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,color_transfer,color_primaries", "-of", "compact", output).CombinedOutput()
			if err != nil || !strings.Contains(string(probe), "width=1920|height=800") || !strings.Contains(string(probe), "color_primaries=bt709") {
				t.Fatalf("probe: %s %v", probe, err)
			}
			if decoded, err := exec.CommandContext(ctx, "ffmpeg", "-v", "error", "-i", output, "-f", "null", "-").CombinedOutput(); err != nil {
				t.Fatalf("decode: %s %v", decoded, err)
			}
			t.Logf("output=%s elapsed=%s", output, time.Since(started))
		})
	}
	t.Run("browser-webm-window", func(t *testing.T) {
		index := 2
		input := Input{UserID: 1, Path: "/window.mkv", Identity: "fixture", SourcePath: source, VideoCodec: "hevc", VideoWidth: 3840, VideoHeight: 1600, HDR: true, DurationSeconds: 12.47, StartSeconds: 2, WindowSeconds: 8, SessionID: "rk-window", SubtitleStream: &index}
		var job Job
		status, _, err := service.ReserveWebM(input, func(candidate Job) (string, error) { job = candidate; return "window", nil })
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
		defer cancel()
		if err := service.RunWithProgress(ctx, job, func(p Progress) error {
			if p.Method != "" && p.Method != "hybrid" {
				t.Errorf("incorrect WebM method: %+v", p)
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
		output, state, err := service.Asset(status.ID, 1, "index.webm")
		if err != nil || state != StateCompleted {
			t.Fatalf("%s %s %v", output, state, err)
		}
		probe, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height:format=duration", "-of", "json", output).CombinedOutput()
		var metadata struct {
			Streams []struct {
				Codec         string `json:"codec_name"`
				Width, Height int
			}
			Format struct{ Duration string }
		}
		if err != nil || json.Unmarshal(probe, &metadata) != nil || len(metadata.Streams) == 0 {
			t.Fatalf("probe: %s %v", probe, err)
		}
		video := metadata.Streams[0]
		// RGA fits the requested even rectangle to the source ratio; rounding
		// can shave several pixels off the nominal 1280 maximum.
		if video.Codec != "vp9" || video.Width < 1272 || video.Width > 1280 || video.Height != 532 {
			t.Fatalf("invalid fit: %s", probe)
		}
		if duration := progressNumber(metadata.Format.Duration); duration < 7.8 || duration > 8.3 {
			t.Fatalf("incorrect window duration: %s", probe)
		}
		t.Logf("window=%s source start=2 duration=8", output)
	})
	t.Run("browser-webm-without-subtitle", func(t *testing.T) {
		input := Input{UserID: 1, Path: "/no-subtitle.mkv", Identity: "fixture", SourcePath: source, VideoCodec: "hevc", VideoWidth: 3840, VideoHeight: 1600, HDR: true, DurationSeconds: 12.47, WindowSeconds: 8, SessionID: "rk-no-subtitle"}
		var job Job
		status, _, err := service.ReserveWebM(input, func(candidate Job) (string, error) { job = candidate; return "no-subtitle", nil })
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
		defer cancel()
		if err := service.Run(ctx, job); err != nil {
			t.Fatal(err)
		}
		output, state, err := service.Asset(status.ID, 1, "index.webm")
		if err != nil || state != StateCompleted {
			t.Fatalf("%s %s %v", output, state, err)
		}
		if decoded, err := exec.CommandContext(ctx, "ffmpeg", "-v", "error", "-i", output, "-f", "null", "-").CombinedOutput(); err != nil {
			t.Fatalf("decode: %s %v", decoded, err)
		}
	})
}
