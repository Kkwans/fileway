package fbhttp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"math"
	"net/http"
	"os/exec"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
)

const (
	videoPreviewMaxDimension       = 256
	videoPreviewShortDuration      = 5
	videoPreviewLongDuration       = 60 * 60
	videoPreviewBlackLumaThreshold = 0.08
)

type ffmpegPreviewService struct {
	workers chan struct{}
}

type videoProbeDocument struct {
	Streams []videoProbeStream `json:"streams"`
	Format  struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

type videoProbeStream struct {
	Index          int    `json:"index"`
	CodecType      string `json:"codec_type"`
	CodecName      string `json:"codec_name"`
	Duration       string `json:"duration"`
	PixelFormat    string `json:"pix_fmt"`
	ColorSpace     string `json:"color_space"`
	ColorTransfer  string `json:"color_transfer"`
	ColorPrimaries string `json:"color_primaries"`
	Disposition    struct {
		AttachedPic int `json:"attached_pic"`
	} `json:"disposition"`
	Tags map[string]string `json:"tags"`
}

type decodedVideoPreview struct {
	data []byte
	luma float64
}

func newFFmpegPreviewService(workers int) *ffmpegPreviewService {
	if workers < 1 {
		workers = 1
	}
	if workers > 2 {
		workers = 2
	}
	return &ffmpegPreviewService{workers: make(chan struct{}, workers)}
}

func (s *ffmpegPreviewService) create(ctx context.Context, file *files.FileInfo) ([]byte, error) {
	select {
	case s.workers <- struct{}{}:
		defer func() { <-s.workers }()
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}

	probe, err := probeVideo(ctx, file.RealPath())
	if err != nil {
		return nil, err
	}

	// Matroska/MP4 files may carry a cover.jpg/mjpeg stream. It is both faster
	// and more reliable than decoding an arbitrary movie frame, so it always
	// wins when it can be decoded.
	for _, stream := range probe.attachedPictureStreams() {
		cover, coverErr := renderVideoPreview(ctx, file.RealPath(), stream, nil, false)
		if coverErr == nil && validCachedPreview(cover) {
			return cover, nil
		}
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return nil, ctxErr
		}
	}

	stream, ok := probe.primaryVideoStream()
	if !ok {
		return nil, fmt.Errorf("视频没有可解码的视频流")
	}
	duration, err := probe.duration()
	if err != nil {
		return nil, err
	}

	var (
		frames     [][]byte
		lastErr    error
		candidates = videoPreviewCandidates(duration)
	)
	for _, candidate := range candidates {
		at := normalizeVideoPreviewTime(candidate, duration)
		frame, frameErr := renderVideoPreview(ctx, file.RealPath(), stream, &at, probe.isHDR())
		if frameErr != nil {
			lastErr = frameErr
			if ctxErr := context.Cause(ctx); ctxErr != nil {
				return nil, ctxErr
			}
			continue
		}
		if luma, decodable := previewLuma(frame); !decodable {
			lastErr = fmt.Errorf("FFmpeg 生成了无法解码的视频封面")
			continue
		} else {
			frames = append(frames, frame)
			if !isBlackLuma(luma) {
				return frame, nil
			}
		}
	}
	if selected, ok := selectVideoPreview(frames); ok {
		return selected, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("FFmpeg 未生成有效的视频封面")
}

// selectVideoPreview returns the first decodable, non-black candidate. If all
// candidates are black, the brightest decodable candidate is still preferable
// to a blank placeholder. It is kept separate from FFmpeg execution so the
// fallback policy remains deterministic and unit-testable.
func selectVideoPreview(frames [][]byte) ([]byte, bool) {
	var best decodedVideoPreview
	bestFound := false
	for _, frame := range frames {
		luma, ok := previewLuma(frame)
		if !ok {
			continue
		}
		if !isBlackLuma(luma) {
			return frame, true
		}
		if !bestFound || luma > best.luma {
			best = decodedVideoPreview{data: frame, luma: luma}
			bestFound = true
		}
	}
	if !bestFound {
		return nil, false
	}
	return best.data, true
}

func probeVideo(ctx context.Context, path string) (videoProbeDocument, error) {
	ffprobePath, err := LookupFFprobe()
	if err != nil {
		return videoProbeDocument{}, fmt.Errorf("FFprobe 不可用: %w", err)
	}
	command := exec.CommandContext(ctx, ffprobePath,
		"-hide_banner", "-loglevel", "error",
		"-probesize", "8M", "-analyzeduration", "5M",
		"-show_streams", "-show_format", "-of", "json", path,
	)
	var output, stderr bytes.Buffer
	command.Stdout = &output
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return videoProbeDocument{}, ctxErr
		}
		return videoProbeDocument{}, fmt.Errorf("FFprobe 视频信息探测失败: %s: %w", strings.TrimSpace(stderr.String()), err)
	}
	var document videoProbeDocument
	if err := json.Unmarshal(output.Bytes(), &document); err != nil {
		return videoProbeDocument{}, fmt.Errorf("FFprobe 视频信息解析失败: %w", err)
	}
	return document, nil
}

func (document videoProbeDocument) duration() (float64, error) {
	if duration, err := strconv.ParseFloat(document.Format.Duration, 64); err == nil && duration > 0 && !math.IsInf(duration, 0) && !math.IsNaN(duration) {
		return duration, nil
	}
	for _, stream := range document.Streams {
		if stream.CodecType != "video" || stream.Disposition.AttachedPic == 1 {
			continue
		}
		if duration, err := strconv.ParseFloat(stream.Duration, 64); err == nil && duration > 0 {
			return duration, nil
		}
	}
	return 0, fmt.Errorf("FFprobe 未返回有效的视频时长")
}

func (document videoProbeDocument) attachedPictureStreams() []videoProbeStream {
	streams := make([]videoProbeStream, 0, 1)
	for _, stream := range document.Streams {
		if stream.CodecType == "video" && stream.Disposition.AttachedPic == 1 {
			streams = append(streams, stream)
		}
	}
	return streams
}

func (document videoProbeDocument) primaryVideoStream() (videoProbeStream, bool) {
	for _, stream := range document.Streams {
		if stream.CodecType == "video" && stream.Disposition.AttachedPic != 1 {
			return stream, true
		}
	}
	return videoProbeStream{}, false
}

func (document videoProbeDocument) isHDR() bool {
	stream, ok := document.primaryVideoStream()
	if !ok {
		return false
	}
	values := strings.ToLower(strings.Join([]string{
		stream.ColorTransfer,
		stream.ColorPrimaries,
		stream.ColorSpace,
		stream.PixelFormat,
	}, " "))
	return strings.Contains(values, "smpte2084") ||
		strings.Contains(values, "arib-std-b67") ||
		strings.Contains(values, "bt2020")
}

func videoPreviewCandidates(duration float64) []float64 {
	switch {
	case duration < videoPreviewShortDuration:
		return []float64{1}
	case duration <= videoPreviewLongDuration:
		return []float64{5}
	default:
		return []float64{60, 120}
	}
}

func normalizeVideoPreviewTime(candidate, duration float64) float64 {
	if duration <= 0 || math.IsNaN(duration) || math.IsInf(duration, 0) {
		return 0
	}
	// Seeking exactly at EOF is unreliable. Keep the requested product
	// timestamp whenever possible, but move it just inside very short files.
	upperBound := duration - 0.1
	if upperBound <= 0 {
		upperBound = duration / 2
	}
	if upperBound < 0 {
		return 0
	}
	return math.Min(math.Max(candidate, 0), upperBound)
}

func renderVideoPreview(ctx context.Context, path string, stream videoProbeStream, timestamp *float64, hdr bool) ([]byte, error) {
	filter := videoPreviewFilter(hdr, timestamp != nil)
	data, err := runFFmpegPreview(ctx, path, stream.Index, timestamp, filter)
	if err == nil || !hdr || timestamp == nil {
		return data, err
	}
	// Some files advertise HDR metadata that their decoder cannot pass through
	// zscale. A normal BT.709 decode is a safer fallback than returning a black
	// poster or failing the whole listing.
	return runFFmpegPreview(ctx, path, stream.Index, timestamp, videoPreviewFilter(false, true))
}

func runFFmpegPreview(ctx context.Context, path string, streamIndex int, timestamp *float64, filter string) ([]byte, error) {
	ffmpegPath, err := lookupFFmpeg()
	if err != nil {
		return nil, fmt.Errorf("FFmpeg 不可用: %w", err)
	}
	args := ffmpegPreviewArgs(path, streamIndex, timestamp, filter)
	command := exec.CommandContext(ctx, ffmpegPath, args...)
	var output, stderr bytes.Buffer
	command.Stdout = &output
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if ctxErr := context.Cause(ctx); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, fmt.Errorf("FFmpeg 视频封面生成失败: %s: %w", strings.TrimSpace(stderr.String()), err)
	}
	if !validCachedPreview(output.Bytes()) {
		return nil, fmt.Errorf("FFmpeg 未生成有效的视频封面")
	}
	return output.Bytes(), nil
}

func ffmpegPreviewArgs(path string, streamIndex int, timestamp *float64, filter string) []string {
	args := []string{"-hide_banner", "-loglevel", "error"}
	if timestamp != nil {
		args = append(args, "-ss", strconv.FormatFloat(*timestamp, 'f', 3, 64))
	}
	args = append(args,
		"-i", path,
		"-map", fmt.Sprintf("0:%d", streamIndex),
		"-an", "-sn", "-dn", "-frames:v", "1",
		"-vf", filter,
		"-threads", "1", "-filter_threads", "1",
		"-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
	)
	return args
}

func videoPreviewFilter(hdr, useThumbnail bool) string {
	parts := make([]string, 0, 6)
	if hdr {
		parts = append(parts,
			"zscale=transfer=linear:primaries=bt2020:matrix=bt2020nc",
			"format=gbrpf32le",
			"tonemap=tonemap=hable:desat=0",
			"zscale=transfer=bt709:primaries=bt709:matrix=bt709",
			"format=yuv420p",
		)
	}
	if useThumbnail {
		parts = append(parts, "thumbnail=n=48")
	}
	parts = append(parts, fmt.Sprintf("scale=%d:%d:force_original_aspect_ratio=decrease", videoPreviewMaxDimension, videoPreviewMaxDimension))
	return strings.Join(parts, ",")
}

func previewLuma(data []byte) (float64, bool) {
	decoded, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return 0, false
	}
	bounds := decoded.Bounds()
	if bounds.Empty() {
		return 0, false
	}
	step := maxInt(1, maxInt(bounds.Dx(), bounds.Dy())/128)
	var total float64
	var count int
	for y := bounds.Min.Y; y < bounds.Max.Y; y += step {
		for x := bounds.Min.X; x < bounds.Max.X; x += step {
			r, g, b, _ := decoded.At(x, y).RGBA()
			total += (0.2126*float64(r) + 0.7152*float64(g) + 0.0722*float64(b)) / 65535
			count++
		}
	}
	if count == 0 {
		return 0, false
	}
	return total / float64(count), true
}

func isBlackLuma(luma float64) bool {
	return luma <= videoPreviewBlackLumaThreshold
}

func maxInt(left, right int) int {
	if left > right {
		return left
	}
	return right
}

func handleVideoPreview(
	w http.ResponseWriter,
	r *http.Request,
	fileCache FileCache,
	coordinator *previewCoordinator,
	ffmpeg *ffmpegPreviewService,
	file *files.FileInfo,
	previewSize PreviewSize,
) (int, error) {
	if previewSize != PreviewSizeThumb {
		return http.StatusNotImplemented, fmt.Errorf("视频仅支持缩略图封面")
	}

	cacheKey := previewCacheKey(file, previewSize)
	preview, ok, err := loadPreviewCache(r.Context(), fileCache, cacheKey)
	if err != nil {
		return errToStatus(err), err
	}
	if !ok {
		preview, err = coordinator.Do(r.Context(), cacheKey, func(ctx context.Context) ([]byte, error) {
			if cached, exists, loadErr := loadPreviewCache(ctx, fileCache, cacheKey); loadErr != nil {
				return nil, loadErr
			} else if exists {
				return cached, nil
			}
			if ffmpeg == nil {
				return nil, errors.New("视频缩略图生成器不可用")
			}
			generated, generateErr := ffmpeg.create(ctx, file)
			if generateErr != nil {
				return nil, generateErr
			}
			storePreviewCache(ctx, fileCache, cacheKey, generated)
			return generated, nil
		})
		if err != nil {
			if errors.Is(err, errPreviewCoolingDown) {
				return http.StatusServiceUnavailable, fmt.Errorf("视频封面生成暂时冷却，请稍后重试")
			}
			return errToStatus(err), err
		}
	}

	w.Header().Set("Cache-Control", previewCacheControl)
	http.ServeContent(w, r, file.Name+".jpg", file.ModTime, bytes.NewReader(preview))
	return 0, nil
}
