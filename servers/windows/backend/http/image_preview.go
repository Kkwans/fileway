package fbhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os/exec"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/img"
)

// Large JPEGs use a bounded native-first policy. The native Go pipeline can
// reuse an embedded EXIF thumbnail and avoids starting a new FFmpeg process;
// that matters on the NAS ARM CPU where process startup and a second decoder
// can make a viewer wait several seconds. Very large files remain on the
// FFmpeg path to keep peak Go decoder memory bounded.
const ffmpegImagePreviewMinBytes = 4 * 1024 * 1024
const nativeImagePreviewMaxBytes = 16 * 1024 * 1024

type ffmpegImagePreviewService struct {
	workers chan struct{}
}

type imagePreviewGenerator interface {
	create(context.Context, *files.FileInfo, PreviewSize) ([]byte, error)
}

func newFFmpegImagePreviewService(workers int) *ffmpegImagePreviewService {
	if workers < 1 {
		workers = 1
	}
	if workers > 1 {
		workers = 1
	}
	return &ffmpegImagePreviewService{workers: make(chan struct{}, workers)}
}

func (s *ffmpegImagePreviewService) create(
	ctx context.Context,
	file *files.FileInfo,
	size PreviewSize,
) ([]byte, error) {
	select {
	case s.workers <- struct{}{}:
		defer func() { <-s.workers }()
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}

	ffmpegPath, err := lookupFFmpeg()
	if err != nil {
		return nil, fmt.Errorf("FFmpeg 不可用: %w", err)
	}

	filter, quality, err := ffmpegImageFilter(size)
	if err != nil {
		return nil, err
	}

	var output bytes.Buffer
	var stderr bytes.Buffer
	command := exec.CommandContext(ctx, ffmpegPath, ffmpegImageArgs(file.RealPath(), filter, quality)...)
	command.Stdout = &output
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			return nil, context.Cause(ctx)
		}
		return nil, fmt.Errorf("FFmpeg 图片预览生成失败: %s: %w", stderr.String(), err)
	}
	if !validCachedPreview(output.Bytes()) {
		return nil, fmt.Errorf("FFmpeg 未生成有效的图片预览")
	}
	return output.Bytes(), nil
}

func ffmpegImageArgs(source, filter, quality string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error",
		"-i", source,
		"-map", "0:v:0", "-frames:v", "1",
		"-vf", filter,
		// Keep the global image worker at one task, but let the active FFmpeg
		// process use two internal threads. On the NAS ARM host this avoids
		// making a single cold thumbnail wait several seconds on one core.
		"-threads", "2", "-filter_threads", "2",
		"-q:v", quality,
		"-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
	}
}

func ffmpegImageFilter(size PreviewSize) (filter, quality string, err error) {
	switch size {
	case PreviewSizeBig:
		return "scale=1080:1080:force_original_aspect_ratio=decrease", "3", nil
	case PreviewSizeThumb:
		// A listing thumbnail is intentionally low quality.  Fast bilinear
		// scaling avoids spending seconds on a full-quality downsample of
		// 8K/10K JPEGs while keeping the real image content recognizable.
		return "scale=256:256:force_original_aspect_ratio=increase:flags=fast_bilinear,crop=256:256", "5", nil
	default:
		return "", "", fmt.Errorf("不支持的图片预览尺寸 %s", size.String())
	}
}

func shouldUseFFmpegImagePreview(file *files.FileInfo, size PreviewSize) bool {
	if file == nil || file.Size < ffmpegImagePreviewMinBytes {
		return false
	}
	if size != PreviewSizeBig && size != PreviewSizeThumb {
		return false
	}
	ext := strings.ToLower(file.Extension)
	return ext == ".jpg" || ext == ".jpeg"
}

func shouldPreferNativeImagePreview(file *files.FileInfo, size PreviewSize) bool {
	if !shouldUseFFmpegImagePreview(file, size) {
		return false
	}
	if size == PreviewSizeThumb {
		return true
	}
	return size == PreviewSizeBig && file.Size <= nativeImagePreviewMaxBytes
}

// A full-size preview is requested immediately after the real thumbnail in
// the image viewer. When the viewer opts into warm=big, decode a large JPEG
// only once, cache that result, and derive the thumbnail from the decoded
// preview bytes. Listing thumbnails do not opt in and keep their cheap
// single-size behavior.
func shouldWarmLargeJPEGPreview(r *http.Request, file *files.FileInfo) bool {
	return r != nil && r.URL.Query().Get("warm") == "big" &&
		shouldUseFFmpegImagePreview(file, PreviewSizeThumb)
}

func createLargeJPEGThumbnailWarmup(
	ctx context.Context,
	imgSvc ImgService,
	source imagePreviewGenerator,
	fileCache FileCache,
	file *files.FileInfo,
) ([]byte, error) {
	bigKey := previewCacheKey(file, PreviewSizeBig)
	bigPreview, ok, err := loadPreviewCache(ctx, fileCache, bigKey)
	if err != nil {
		return nil, err
	}
	if !ok {
		bigPreview, err = source.create(ctx, file, PreviewSizeBig)
		if err != nil {
			return nil, err
		}
		storePreviewCache(ctx, fileCache, bigKey, bigPreview)
	}
	return createLargeJPEGThumbnailFromBigPreview(ctx, imgSvc, fileCache, file, bigPreview)
}

func createLargeJPEGThumbnailFromBigPreview(
	ctx context.Context,
	imgSvc ImgService,
	fileCache FileCache,
	file *files.FileInfo,
	bigPreview []byte,
) ([]byte, error) {
	thumbnail := &bytes.Buffer{}
	if err := imgSvc.Resize(
		ctx,
		bytes.NewReader(bigPreview),
		256,
		256,
		thumbnail,
		img.WithMode(img.ResizeModeFill),
		img.WithQuality(img.QualityLow),
		img.WithFormat(img.FormatJpeg),
	); err != nil {
		return nil, err
	}
	result := thumbnail.Bytes()
	storePreviewCache(ctx, fileCache, previewCacheKey(file, PreviewSizeThumb), result)
	// The warm request is used by the preview page itself. Return the already
	// decoded large preview so the browser can promote the placeholder image to
	// the viewer without issuing a second, identical decode request. The exact
	// 256px thumbnail remains cached above for listing cards and other callers.
	return bigPreview, nil
}
