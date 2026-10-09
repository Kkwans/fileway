package hls

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Export shares the compatibility worker budget and progress contract. Source
// probing runs only after a worker is acquired, so a batch cannot spawn an
// unbounded number of simultaneous ffprobe processes.
func (service *Service) Export(ctx context.Context, prepare func() (Job, error), output, previewID string, report func(Progress) error) error {
	if err := report(Progress{Phase: "queued", UpdatedAt: time.Now().UnixMilli()}); err != nil {
		return err
	}
	select {
	case service.workers <- struct{}{}:
		defer func() { <-service.workers }()
	case <-ctx.Done():
		return ctx.Err()
	}
	if err := report(Progress{Phase: "preparing", StartedAt: time.Now().UnixMilli(), UpdatedAt: time.Now().UnixMilli()}); err != nil {
		return err
	}
	job, err := prepare()
	if err != nil {
		return err
	}
	preview := service.ExportDirectory(previewID)
	if err := os.MkdirAll(preview, 0o700); err != nil {
		return err
	}
	complete := false
	defer func() {
		if !complete {
			_ = os.RemoveAll(preview)
		}
	}()
	relative, err := filepath.Rel(filepath.Dir(output), preview)
	if err != nil {
		return err
	}
	relative = filepath.ToSlash(relative)
	if strings.ContainsAny(relative, ":|[]'\\") {
		return fmt.Errorf("转码缓存路径包含不支持的封装字符")
	}
	job.report = func(p Progress) error { p.PlayableSeconds = PlayableSeconds(preview); return report(p) }
	command := exec.CommandContext(ctx, service.ffmpegPath, exportTeeArgs(job, filepath.Base(output), relative)...)
	command.Dir = filepath.Dir(output)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := service.runProgress(command, job); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return fmt.Errorf("视频转码失败: %s", message)
	}
	info, err := os.Stat(output)
	if err != nil || info.Size() == 0 {
		return fmt.Errorf("转码未生成有效视频")
	}
	if err := service.registerExport(job, previewID); err != nil {
		return err
	}
	complete = true
	return nil
}

func (service *Service) ExportDirectory(id string) string {
	digest := sha256.Sum256([]byte(id))
	return service.entryDir(hex.EncodeToString(digest[:]))
}

func (service *Service) registerExport(job Job, id string) error {
	digest := sha256.Sum256([]byte(id))
	key := hex.EncodeToString(digest[:])
	size, err := directorySize(service.entryDir(key))
	if err != nil {
		return err
	}
	service.mu.Lock()
	service.entries[key] = &entry{Status: Status{ID: key, TaskID: id, UserID: job.UserID, Path: job.Path, Identity: "export:" + id, Profile: DefaultCopyProfile, State: StateCompleted, DurationSeconds: job.DurationSeconds, UpdatedAt: time.Now().UnixMilli(), SizeBytes: size}}
	service.mu.Unlock()
	if err := service.persist(key); err != nil {
		return err
	}
	service.mu.Lock()
	service.cleanupLocked(key)
	service.mu.Unlock()
	return nil
}

func (service *Service) TouchExport(id string) {
	digest := sha256.Sum256([]byte(id))
	key := hex.EncodeToString(digest[:])
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[key]; current != nil {
		current.LastAccessAt = time.Now().UnixMilli()
		current.leaseUntil = time.Now().Add(playingLease)
	}
}

func PlayableSeconds(directory string) float64 {
	file, err := os.Open(filepath.Join(directory, "index.m3u8"))
	if err != nil {
		return 0
	}
	defer func() { _ = file.Close() }()
	seconds := 0.0
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#EXTINF:") {
			value, _, _ := strings.Cut(strings.TrimPrefix(line, "#EXTINF:"), ",")
			duration, err := strconv.ParseFloat(value, 64)
			if err == nil && duration > 0 {
				seconds += duration
			}
		}
	}
	return seconds
}

func exportTeeArgs(job Job, output, preview string) []string {
	var args []string
	if IsMP4CopyProfile(job.Profile) {
		args = []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-i", job.SourcePath, "-map", "0:v:0", "-map", selectedAudioMap(job.AudioStream), "-c:v", "copy", "-c:a", "copy"}
		if job.Profile == DefaultMP4AudioProfile {
			args = append(args, "-c:a", "aac", "-b:a", "192k", "-ac", "2")
		}
	} else {
		args = mp4ExportArgs(job, "unused")
		for i, arg := range args {
			if arg == "-movflags" {
				args = args[:i]
				break
			}
		}
	}
	mux := fmt.Sprintf("[f=hls:hls_time=4:hls_list_size=0:hls_playlist_type=event:hls_flags=independent_segments+temp_file:hls_segment_filename=%s/segment-%%06d.ts]%s/index.m3u8|[f=mp4:movflags=+faststart]%s", preview, preview, output)
	return append(args, "-flags", "+global_header", "-f", "tee", mux)
}

func mp4ExportArgs(job Job, output string) []string {
	if IsMP4CopyProfile(job.Profile) {
		return append([]string{"-y"}, copyTrackArgs(mp4CopyArgs(job.SourcePath, output), job)...)
	}
	args := ffmpegTrackArgs(job.SourcePath, "unused", "unused", profileMaxWidth(job.Profile), profileMaxHeight(job.Profile), job.SubtitleStream, job.AudioStream, job.HDR)
	for i, arg := range args {
		if arg == "-f" {
			args = args[:i]
			break
		}
	}
	args = append([]string{"-y"}, args...)
	return append(args, "-crf", "18", "-movflags", "+faststart", "-f", "mp4", output)
}

func MP4ExportProfile(input Input, quality string) string {
	if input.SubtitleStream == nil && !input.HDR && CanCopyVideo(input) && QualityPreservesSource(quality, input.VideoWidth, input.VideoHeight) {
		if input.AudioCodec == "" || input.AudioCodec == "aac" {
			return DefaultMP4CopyProfile
		}
		return DefaultMP4AudioProfile
	}
	return ProfileForDimensions(quality, input.VideoWidth, input.VideoHeight)
}
