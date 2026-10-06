package fbhttp

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os/exec"
	"strconv"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const (
	spriteColumns    = 10
	spriteTileWidth  = 160
	spriteMaxTiles   = 100
	spriteCacheLabel = "video-sprite-v1"
)

type videoSpriteResponse struct {
	Path   string `json:"path"`
	Number int    `json:"number"`
	Column int    `json:"column"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	URL    string `json:"url"`
}

func videoSpriteCacheKey(file *files.FileInfo, interval float64) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf(
		"%s|%s|%d|%d|%.4f|%d",
		spriteCacheLabel,
		file.Path,
		file.Size,
		file.ModTime.Unix(),
		interval,
		spriteColumns,
	)))
	return "sprite:" + hex.EncodeToString(sum[:])
}

func spriteIntervalForDuration(duration float64) float64 {
	if duration <= 0 {
		return 10
	}
	interval := duration / float64(spriteMaxTiles)
	if interval < 1 {
		interval = 1
	}
	if interval > 30 {
		interval = 30
	}
	return interval
}

func generateVideoSprite(ctx context.Context, realPath string, interval float64) ([]byte, int, int, int, error) {
	ffmpegBin, err := LookupFFmpeg()
	if err != nil {
		return nil, 0, 0, 0, fmt.Errorf("ffmpeg 不可用: %w", err)
	}
	number := spriteMaxTiles
	rows := spriteColumns
	vf := fmt.Sprintf(
		"fps=1/%.6f,scale=%d:-2,tile=%dx%d",
		interval,
		spriteTileWidth,
		spriteColumns,
		rows,
	)
	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-i", realPath,
		"-vf", vf,
		"-frames:v", "1",
		"-q:v", "5",
		"-f", "image2",
		"-vcodec", "mjpeg",
		"pipe:1",
	}
	cmd := exec.CommandContext(ctx, ffmpegBin, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return nil, 0, 0, 0, fmt.Errorf("雪碧图生成失败: %s", msg)
	}
	if stdout.Len() == 0 {
		return nil, 0, 0, 0, fmt.Errorf("雪碧图为空")
	}
	height := spriteTileWidth * 9 / 16
	return stdout.Bytes(), number, spriteColumns, height, nil
}

func loadOrBuildVideoSprite(ctx context.Context, cache FileCache, d *data, file *files.FileInfo) (videoSpriteResponse, []byte, error) {
	interval := spriteIntervalForDuration(0)
	probe, probeErr := probeVideo(ctx, file.RealPath())
	if probeErr == nil {
		if dur, err := strconv.ParseFloat(probe.Format.Duration, 64); err == nil && dur > 0 {
			interval = spriteIntervalForDuration(dur)
		}
	}
	key := videoSpriteCacheKey(file, interval)
	if cached, ok, err := loadPreviewCache(ctx, cache, key); err == nil && ok && len(cached) > 0 {
		metaRaw, metaOK, _ := loadPreviewCache(ctx, cache, key+":meta")
		number, column, width, height := spriteMaxTiles, spriteColumns, spriteTileWidth, spriteTileWidth*9/16
		if metaOK && len(metaRaw) > 0 {
			var m videoSpriteResponse
			if json.Unmarshal(metaRaw, &m) == nil && m.Number > 0 {
				number, column, width, height = m.Number, m.Column, m.Width, m.Height
			}
		}
		return videoSpriteResponse{
			Path: file.Path, Number: number, Column: column, Width: width, Height: height,
		}, cached, nil
	}

	sheet, number, column, height, err := generateVideoSprite(ctx, file.RealPath(), interval)
	width := spriteTileWidth
	if err != nil {
		return videoSpriteResponse{}, nil, err
	}
	if probeErr == nil {
		if dur, derr := strconv.ParseFloat(probe.Format.Duration, 64); derr == nil && dur > 0 {
			est := int(dur/interval + 0.5)
			if est >= 1 && est <= spriteMaxTiles {
				number = est
			}
		}
	}
	meta := videoSpriteResponse{
		Path:   file.Path,
		Number: number,
		Column: column,
		Width:  width,
		Height: height,
	}
	if raw, jerr := json.Marshal(meta); jerr == nil {
		storePreviewCache(ctx, cache, key, sheet)
		storePreviewCache(ctx, cache, key+":meta", raw)
	}
	return meta, sheet, nil
}

func mediaSpriteFile(r *http.Request, d *data) (*files.FileInfo, int, error) {
	if !d.user.Perm.Download {
		return nil, http.StatusForbidden, fmt.Errorf("没有读取媒体的权限")
	}
	value := r.URL.Query().Get("path")
	if value == "" {
		return nil, http.StatusBadRequest, fmt.Errorf("媒体路径不能为空")
	}
	value = pathmeta.Clean(value)
	file, err := files.NewFileInfo(&files.FileOptions{
		Fs: d.user.Fs, Path: value, Modify: d.user.Perm.Modify,
		Expand: true, SkipSubtitles: true,
		ReadHeader: d.server.TypeDetectionByHeader,
		Checker: d,
	})
	if err != nil {
		return nil, errToStatus(err), err
	}
	if file.IsDir {
		return nil, http.StatusBadRequest, fmt.Errorf("目录无法生成雪碧图")
	}
	if file.Type != "" && file.Type != "video" {
		return nil, http.StatusBadRequest, fmt.Errorf("仅视频文件支持雪碧图")
	}
	return file, 0, nil
}

func mediaSpriteMetaHandler(cache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		file, status, err := mediaSpriteFile(r, d)
		if err != nil {
			return status, err
		}
		if cache == nil {
			return http.StatusInternalServerError, fmt.Errorf("雪碧图缓存未配置")
		}
		meta, _, err := loadOrBuildVideoSprite(r.Context(), cache, d, file)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		base := strings.TrimSuffix(d.server.BaseURL, "/")
		meta.URL = base + "/api/media/sprite.jpg?path=" + url.QueryEscape(file.Path)
		return renderJSON(w, r, meta)
	})
}

func mediaSpriteImageHandler(cache FileCache) handleFunc {
	return withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
		file, status, err := mediaSpriteFile(r, d)
		if err != nil {
			return status, err
		}
		if cache == nil {
			return http.StatusInternalServerError, fmt.Errorf("雪碧图缓存未配置")
		}
		_, sprite, err := loadOrBuildVideoSprite(r.Context(), cache, d, file)
		if err != nil {
			return http.StatusInternalServerError, err
		}
		w.Header().Set("Cache-Control", previewCacheControl)
		w.Header().Set("Content-Type", "image/jpeg")
		http.ServeContent(w, r, file.Name+".sprite.jpg", file.ModTime, bytes.NewReader(sprite))
		return 0, nil
	})
}
