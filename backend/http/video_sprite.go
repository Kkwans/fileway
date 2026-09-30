package fbhttp

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"math"
	"net/http"
	"net/url"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const (
	spriteMaxTiles  = 100
	spriteColumns   = 10
	spriteMaxWidth  = 160
	spriteMaxHeight = 90
	spriteVersion   = "video-sprite-v3-seek-full-timeline"
)

type videoSpriteResponse struct {
	Path     string  `json:"path"`
	Number   int     `json:"number"`
	Column   int     `json:"column"`
	Width    int     `json:"width"`
	Height   int     `json:"height"`
	Interval float64 `json:"interval"`
	URL      string  `json:"url"`
	State    string  `json:"state,omitempty"`
	Error    string  `json:"error,omitempty"`
	HDR      bool    `json:"-"`
}

type videoSpriteService struct {
	cache       FileCache
	coordinator *previewCoordinator
	workers     chan struct{}
}

func newVideoSpriteService(cache FileCache) *videoSpriteService {
	return &videoSpriteService{cache: cache, coordinator: newPreviewCoordinator(), workers: make(chan struct{}, 1)}
}

func spriteTileGeometry(width, height int) (int, int) {
	if width <= 0 || height <= 0 {
		return spriteMaxWidth, spriteMaxHeight
	}
	ratio := float64(width) / float64(height)
	tileWidth, tileHeight := spriteMaxWidth, int(math.Round(float64(spriteMaxWidth)/ratio))
	if tileHeight > spriteMaxHeight {
		tileHeight = spriteMaxHeight
		tileWidth = int(math.Round(float64(spriteMaxHeight) * ratio))
	}
	tileWidth = max(2, tileWidth-tileWidth%2)
	tileHeight = max(2, tileHeight-tileHeight%2)
	return tileWidth, tileHeight
}

func spriteSampling(duration float64) (float64, int) {
	if duration <= 0 {
		return 10, spriteMaxTiles
	}
	number := max(1, min(spriteMaxTiles, int(math.Ceil(duration))))
	return duration / float64(number), number
}

func videoSpriteKey(file *files.FileInfo, meta videoSpriteResponse) string {
	payload := fmt.Sprintf("%s|%s|%d|%d|%.4f|%d|%d|%d", spriteVersion, file.Path, file.Size,
		file.ModTime.UnixNano(), meta.Interval, meta.Number, meta.Width, meta.Height)
	digest := sha256.Sum256([]byte(payload))
	return "sprite:" + hex.EncodeToString(digest[:])
}

func (service *videoSpriteService) loadOrCreate(ctx context.Context, file *files.FileInfo) (videoSpriteResponse, []byte, error) {
	if service.cache == nil {
		return videoSpriteResponse{}, nil, fmt.Errorf("雪碧图缓存未配置")
	}
	meta, err := describeVideoSprite(ctx, file)
	if err != nil {
		return videoSpriteResponse{}, nil, err
	}
	return service.loadForMeta(ctx, file, meta)
}

func describeVideoSprite(ctx context.Context, file *files.FileInfo) (videoSpriteResponse, error) {
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	probe, err := defaultMediaProbe(probeCtx, file.RealPath(), false)
	if err != nil {
		return videoSpriteResponse{}, err
	}
	width, height := spriteTileGeometry(probe.Width, probe.Height)
	interval, number := spriteSampling(probe.Duration)
	columns := min(spriteColumns, number)
	meta := videoSpriteResponse{Path: file.Path, Number: number, Column: columns, Width: width, Height: height, Interval: interval}
	meta.HDR = probe.VideoTransfer == "smpte2084"
	return meta, nil
}

func (service *videoSpriteService) loadForMeta(ctx context.Context, file *files.FileInfo, meta videoSpriteResponse) (videoSpriteResponse, []byte, error) {
	if service.cache == nil {
		return meta, nil, fmt.Errorf("雪碧图缓存未配置")
	}
	key := videoSpriteKey(file, meta)
	if cached, ok, loadErr := loadPreviewCache(ctx, service.cache, key); loadErr == nil && ok && len(cached) > 0 {
		return meta, cached, nil
	}
	data, err := service.coordinator.DoKeepAlive(ctx, key, func(workCtx context.Context) ([]byte, error) {
		workCtx, cancel := context.WithTimeout(workCtx, 3*time.Minute)
		defer cancel()
		if cached, ok, loadErr := loadPreviewCache(workCtx, service.cache, key); loadErr == nil && ok && len(cached) > 0 {
			return cached, nil
		}
		select {
		case service.workers <- struct{}{}:
			defer func() { <-service.workers }()
		case <-workCtx.Done():
			return nil, context.Cause(workCtx)
		}
		generated, generateErr := generateVideoSprite(workCtx, file.RealPath(), meta)
		if generateErr != nil {
			return nil, generateErr
		}
		storePreviewCache(workCtx, service.cache, key, generated)
		return generated, nil
	})
	return meta, data, err
}

func generateVideoSprite(ctx context.Context, source string, meta videoSpriteResponse) ([]byte, error) {
	ffmpegPath, err := exec.LookPath("ffmpeg")
	if err != nil {
		return nil, fmt.Errorf("ffmpeg 不可用: %w", err)
	}
	rows := (meta.Number + meta.Column - 1) / meta.Column
	sheet := image.NewRGBA(image.Rect(0, 0, meta.Column*meta.Width, rows*meta.Height))
	workCtx, cancel := context.WithCancel(ctx)
	type result struct {
		index int
		frame image.Image
		err   error
	}
	jobs := make(chan int, meta.Number)
	for i := 0; i < meta.Number; i++ {
		jobs <- i
	}
	close(jobs)
	results := make(chan result, 2)
	var workers sync.WaitGroup
	// The service admits one sheet; two readers overlap its independent seeks.
	for worker := 0; worker < min(2, meta.Number); worker++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for i := range jobs {
				if workCtx.Err() != nil {
					return
				}
				frame, err := generateSpriteFrame(workCtx, ffmpegPath, source, float64(i)*meta.Interval, meta)
				select {
				case results <- result{i, frame, err}:
				case <-workCtx.Done():
					return
				}
				if err != nil {
					return
				}
			}
		}()
	}
	defer func() { cancel(); workers.Wait() }()
	go func() { workers.Wait(); close(results) }()
	for item := range results {
		if item.err != nil {
			return nil, fmt.Errorf("缩略图 %d 生成失败: %w", item.index+1, item.err)
		}
		x, y := item.index%meta.Column*meta.Width, item.index/meta.Column*meta.Height
		draw.Draw(sheet, image.Rect(x, y, x+meta.Width, y+meta.Height), item.frame, item.frame.Bounds().Min, draw.Src)
	}
	if workCtx.Err() != nil {
		return nil, workCtx.Err()
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, sheet, &jpeg.Options{Quality: 80}); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

func generateSpriteFrame(ctx context.Context, ffmpegPath, source string, timestamp float64, meta videoSpriteResponse) (image.Image, error) {
	args := spriteFrameArgs(source, timestamp, meta.Width, meta.Height)
	if meta.HDR {
		for i, value := range args {
			if value == "-vf" {
				args[i+1] += ",format=yuv420p10le,zscale=t=linear:npl=100,format=gbrpf32le,tonemap=tonemap=hable:desat=0,zscale=p=bt709:t=bt709:m=bt709,format=yuv420p"
			}
		}
	}
	var lastErr error
	// Avoid analyzing all audio/subtitle tracks again for each still. Sources
	// with unusual headers get one bounded retry with a larger probe budget.
	for attempt := 0; attempt < 2; attempt++ {
		command := exec.CommandContext(ctx, ffmpegPath, args...)
		var stdout, stderr bytes.Buffer
		command.Stdout, command.Stderr = &stdout, &stderr
		if err := command.Run(); err != nil {
			lastErr = fmt.Errorf("%w: %s", err, stderr.String())
		} else {
			frame, err := jpeg.Decode(&stdout)
			if err == nil {
				return frame, nil
			}
			lastErr = err
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		for i, arg := range args {
			if arg == "-probesize" || arg == "-analyzeduration" {
				args[i+1] = "1000000"
			}
		}
	}
	return nil, lastErr
}

func spriteFrameArgs(source string, timestamp float64, width, height int) []string {
	return []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "2",
		"-probesize", "32768", "-analyzeduration", "1",
		"-skip_frame", "nokey", "-noaccurate_seek", "-ss", strconv.FormatFloat(timestamp, 'f', 6, 64), "-i", source, "-map", "0:v:0", "-an", "-sn",
		"-vf", fmt.Sprintf("scale=%d:%d:flags=fast_bilinear", width, height), "-filter_threads", "1",
		"-frames:v", "1", "-threads", "1", "-q:v", "5", "-f", "image2", "-vcodec", "mjpeg", "pipe:1"}
}

func videoSpriteFile(r *http.Request, d *data) (*files.FileInfo, int, error) {
	if !d.user.Perm.Download {
		return nil, http.StatusForbidden, fmt.Errorf("没有读取媒体的权限")
	}
	value := r.URL.Query().Get("path")
	if value == "" {
		return nil, http.StatusBadRequest, fmt.Errorf("媒体路径不能为空")
	}
	value = pathmeta.Clean(value)
	file, err := files.NewFileInfo(&files.FileOptions{Fs: d.user.Fs, Path: value, Modify: d.user.Perm.Modify,
		Expand: true, SkipSubtitles: true, ReadHeader: d.server.TypeDetectionByHeader, Checker: d})
	if err != nil {
		return nil, errToStatus(err), err
	}
	if file.IsDir || (file.Type != "" && file.Type != "video") {
		return nil, http.StatusBadRequest, fmt.Errorf("仅视频文件支持雪碧图")
	}
	return file, 0, nil
}

func (service *videoSpriteService) metaHandler() handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		file, status, err := videoSpriteFile(r, d)
		if err != nil {
			return status, err
		}
		meta, err := describeVideoSprite(r.Context(), file)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		ctx, cancel := context.WithTimeout(r.Context(), 600*time.Millisecond)
		defer cancel()
		_, _, err = service.loadForMeta(ctx, file, meta)
		if errors.Is(err, context.DeadlineExceeded) {
			meta.State = "preparing"
			return renderJSONStatus(w, meta, http.StatusAccepted)
		}
		if err != nil {
			meta.State = "failed"
			meta.Error = "预览缩略图暂时不可用，可稍后重试"
			return renderJSON(w, r, meta)
		}
		meta.State = "ready"
		meta.URL = fmt.Sprintf("%s/api/media/sprite.jpg?path=%s", d.server.BaseURL, url.QueryEscape(file.Path))
		return renderJSON(w, r, meta)
	})
}

func (service *videoSpriteService) imageHandler() handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		file, status, err := videoSpriteFile(r, d)
		if err != nil {
			return status, err
		}
		_, data, err := service.loadOrCreate(r.Context(), file)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		w.Header().Set("Cache-Control", previewCacheControl)
		w.Header().Set("Content-Type", "image/jpeg")
		http.ServeContent(w, r, file.Name+".sprite.jpg", file.ModTime, bytes.NewReader(data))
		return 0, nil
	})
}
