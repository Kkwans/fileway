package hls

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	DefaultMaxBytes        = int64(10 << 30)
	DefaultProfile         = "h264-main-720p-aac-hls4-v1"
	DefaultCopyProfile     = "h264-copy-hls-v1"
	DefaultMP4CopyProfile  = "h264-aac-mp4-copy-v1"
	DefaultWebMProfile     = "vp9-720p-opus-webm-v1"
	DefaultWebMCopyProfile = "vp9-opus-webm-copy-v1"
	playingLease           = 2 * time.Minute
	maxFFmpegError         = 8 * 1024
)

var (
	ErrNotFound  = errors.New("HLS cache not found")
	ErrForbidden = errors.New("HLS cache access denied")
	ErrState     = errors.New("HLS cache state does not allow this operation")
	cacheIDRE    = regexp.MustCompile(`^[a-f0-9]{64}$`)
	assetNameRE  = regexp.MustCompile(`^(index\.m3u8|index\.mp4|index\.webm|segment-[0-9]{6}\.ts)$`)
)

type State string

const (
	StateQueued     State = "queued"
	StatePreparing  State = "preparing"
	StateStreamable State = "streamable"
	StateCompleted  State = "completed"
	StateFailed     State = "failed"
	StateCanceled   State = "canceled"
)

type Config struct {
	CacheDir   string
	MaxBytes   int64
	Workers    int
	FFmpegPath string
	Profile    string
}

type Input struct {
	UserID     uint
	Path       string
	Identity   string
	SourcePath string
	VideoCodec string
	AudioCodec string
	// VideoPixelFormat, VideoProfile and VideoBitDepth come from ffprobe. They
	// keep a stream that merely names itself H.264 from being treated as
	// browser-safe when it is actually 10-bit or 4:2:2/4:4:4.
	VideoPixelFormat string
	VideoProfile     string
	VideoBitDepth    int
	// VideoHeight is the source vertical resolution (e.g. 1080), used to pick
	// a sane transcode quality when the user asks for source/4K/2K/etc.
	VideoHeight int
	// DurationSeconds is the probed source duration used to render truthful
	// compatibility progress while a WebM artifact is being generated.
	DurationSeconds float64
}

type Job struct {
	ID              string
	UserID          uint
	Path            string
	Identity        string
	SourcePath      string
	Profile         string
	DurationSeconds float64
}

// IsWebMProfile reports whether a compatibility artifact is a complete WebM
// file rather than an HLS playlist.  The profile is part of the cache key so
// browsers with and without H.264 MSE support do not share incompatible data.
func IsWebMProfile(profile string) bool {
	return profile == DefaultWebMProfile
}

// IsWebMCopyProfile reports a WebM artifact that only remuxes streams already
// supported by Chromium, rather than decoding and re-encoding them.
func IsWebMCopyProfile(profile string) bool {
	return profile == DefaultWebMCopyProfile
}

// IsCopyProfile reports whether the HLS artifact only remuxes streams that
// Chromium can consume.  It avoids a full video encode for MKV/MOV files that
// already contain H.264 video and AAC (or no) audio.
func IsCopyProfile(profile string) bool {
	return profile == DefaultCopyProfile
}

// IsMP4CopyProfile reports a complete MP4 artifact containing the original
// browser-compatible H.264/AAC streams. The source is remuxed, never encoded.
func IsMP4CopyProfile(profile string) bool {
	return profile == DefaultMP4CopyProfile
}

// CanCopyMedia is deliberately conservative: copying an unsupported audio
// stream would produce a fast but unusable compatibility artifact.
func CanCopyMedia(videoCodec, audioCodec string) bool {
	return CanCopyMediaWithDetails(videoCodec, audioCodec, "", "", 0)
}

// CanCopyMediaWithDetails is deliberately conservative. Chromium's broadly
// available MP4 decoder expects 8-bit 4:2:0 H.264; a container carrying H.264
// 10-bit, 4:2:2 or 4:4:4 still needs a compatibility encode even though the
// codec name itself is "h264".
func CanCopyMediaWithDetails(videoCodec, audioCodec, pixelFormat, profile string, bitDepth int) bool {
	videoCodec = strings.ToLower(strings.TrimSpace(videoCodec))
	audioCodec = strings.ToLower(strings.TrimSpace(audioCodec))
	if videoCodec != "h264" || (audioCodec != "" && audioCodec != "aac") {
		return false
	}
	if bitDepth > 8 {
		return false
	}
	pixelFormat = strings.ToLower(strings.TrimSpace(pixelFormat))
	if pixelFormat != "" && pixelFormat != "yuv420p" && pixelFormat != "yuvj420p" {
		return false
	}
	profile = strings.ToLower(strings.TrimSpace(profile))
	for _, unsupported := range []string{"10", "12", "4:2:2", "4:4:4"} {
		if strings.Contains(profile, unsupported) {
			return false
		}
	}
	return true
}

// CanCopyWebMMedia accepts browser-native WebM codec families so MKV/MOV
// files carrying VP8/VP9/AV1 + Opus/Vorbis do not need a compatibility encode.
func CanCopyWebMMedia(videoCodec, audioCodec string) bool {
	videoCodec = strings.ToLower(strings.TrimSpace(videoCodec))
	audioCodec = strings.ToLower(strings.TrimSpace(audioCodec))
	videoOK := videoCodec == "vp8" || videoCodec == "vp9" || videoCodec == "av1"
	audioOK := audioCodec == "" || audioCodec == "opus" || audioCodec == "vorbis"
	return videoOK && audioOK
}

type Status struct {
	ID           string `json:"id"`
	TaskID       string `json:"taskId,omitempty"`
	Path         string `json:"path"`
	Identity     string `json:"identity"`
	Profile      string `json:"profile"`
	State        State  `json:"state"`
	Error        string `json:"error,omitempty"`
	UpdatedAt    int64  `json:"updatedAt"`
	LastAccessAt int64  `json:"lastAccessAt,omitempty"`
	SizeBytes    int64  `json:"sizeBytes,omitempty"`
	// ProcessedSeconds is reported for compatibility transcodes that cannot
	// expose a playable artifact until the complete file is ready.  It is
	// deliberately a duration, not a guessed percentage.
	ProcessedSeconds float64 `json:"processedSeconds,omitempty"`
	// DurationSeconds is the source duration paired with ProcessedSeconds.
	// It may be zero when codec probing was unavailable.
	DurationSeconds float64 `json:"durationSeconds,omitempty"`
	UserID          uint    `json:"userId"`
}

type entry struct {
	Status
	sourcePath string
	leaseUntil time.Time
}

type StartFunc func(job Job) (taskID string, err error)

type Service struct {
	cacheDir   string
	maxBytes   int64
	ffmpegPath string
	profile    string
	workers    chan struct{}

	mu      sync.Mutex
	entries map[string]*entry
}

type cappedBuffer struct {
	bytes.Buffer
	limit int
}

func (buffer *cappedBuffer) Write(payload []byte) (int, error) {
	written := len(payload)
	remaining := buffer.limit - buffer.Len()
	if remaining > 0 {
		if remaining > len(payload) {
			remaining = len(payload)
		}
		_, _ = buffer.Buffer.Write(payload[:remaining])
	}
	return written, nil
}

func New(config Config) (*Service, error) {
	if config.CacheDir == "" {
		return nil, fmt.Errorf("HLS cache directory is required")
	}
	if config.MaxBytes <= 0 {
		config.MaxBytes = DefaultMaxBytes
	}
	if config.Workers < 1 || config.Workers > 2 {
		return nil, fmt.Errorf("HLS processors count must be between 1 and 2")
	}
	if config.FFmpegPath == "" {
		// Prefer service-local bin/ffmpeg before relying on process PATH.
		if exe, err := os.Executable(); err == nil {
			dir := filepath.Dir(exe)
			for _, name := range []string{
				filepath.Join(dir, "bin", "ffmpeg.exe"),
				filepath.Join(dir, "bin", "ffmpeg"),
				filepath.Join(dir, "ffmpeg.exe"),
				filepath.Join(dir, "ffmpeg"),
			} {
				if info, err := os.Stat(name); err == nil && !info.IsDir() {
					config.FFmpegPath = name
					break
				}
			}
		}
		if config.FFmpegPath == "" {
			config.FFmpegPath = "ffmpeg"
		}
	}
	if config.Profile == "" {
		config.Profile = DefaultProfile
	}
	if err := os.MkdirAll(config.CacheDir, 0o700); err != nil {
		return nil, fmt.Errorf("create HLS cache directory: %w", err)
	}
	service := &Service{
		cacheDir: config.CacheDir, maxBytes: config.MaxBytes,
		ffmpegPath: config.FFmpegPath, profile: config.Profile,
		workers: make(chan struct{}, config.Workers), entries: make(map[string]*entry),
	}
	if err := service.loadCompleted(); err != nil {
		return nil, err
	}
	service.mu.Lock()
	service.cleanupLocked("")
	service.mu.Unlock()
	return service, nil
}

func (service *Service) Reserve(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, service.profile, start)
}

// ReserveWithProfile uses an explicit encode/remux profile (e.g. 1080p).
func (service *Service) ReserveWithProfile(input Input, profile string, start StartFunc) (Status, bool, error) {
	if profile == "" {
		profile = service.profile
	}
	return service.reserve(input, profile, start)
}

// ProfileForQuality builds an encode profile from a quality token and source height.
// quality: source|4k|2k|1080p|720p|480p (aliases 2160p/1440p).
func ProfileForQuality(quality string, sourceHeight int) string {
	q := strings.ToLower(strings.TrimSpace(quality))
	switch q {
	case "4k", "2160p", "uhd":
		if sourceHeight > 0 && sourceHeight < 1800 {
			return EncodeProfileForHeight(sourceHeight)
		}
		return "h264-main-2160p-aac-hls4-v1"
	case "2k", "1440p":
		if sourceHeight > 0 && sourceHeight < 1200 {
			return EncodeProfileForHeight(sourceHeight)
		}
		return "h264-main-1440p-aac-hls4-v1"
	case "1080p", "1080":
		if sourceHeight > 0 && sourceHeight < 900 {
			return EncodeProfileForHeight(sourceHeight)
		}
		return "h264-main-1080p-aac-hls4-v1"
	case "720p", "720":
		return "h264-main-720p-aac-hls4-v1"
	case "480p", "480":
		return "h264-main-480p-aac-hls4-v1"
	case "source", "":
		if sourceHeight > 0 {
			return EncodeProfileForHeight(sourceHeight)
		}
		return DefaultProfile
	default:
		return DefaultProfile
	}
}

func EncodeProfileForHeight(h int) string {
	switch {
	case h >= 2000:
		return "h264-main-2160p-aac-hls4-v1"
	case h >= 1300:
		return "h264-main-1440p-aac-hls4-v1"
	case h >= 900:
		return "h264-main-1080p-aac-hls4-v1"
	case h >= 600:
		return "h264-main-720p-aac-hls4-v1"
	default:
		return "h264-main-480p-aac-hls4-v1"
	}
}

// ProfileMaxWidth returns target pixel width; 0 means do not downscale beyond source.
func ProfileMaxWidth(profile string) int {
	p := strings.ToLower(profile)
	switch {
	case strings.Contains(p, "2160p"):
		return 3840
	case strings.Contains(p, "1440p"):
		return 2560
	case strings.Contains(p, "1080p"):
		return 1920
	case strings.Contains(p, "720p"):
		return 1280
	case strings.Contains(p, "480p"):
		return 854
	default:
		return 1280
	}
}

// ReserveCopy creates an HLS playlist by copying already browser-compatible
// H.264/AAC streams.  The container is remuxed, not re-encoded.
func (service *Service) ReserveCopy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultCopyProfile, start)
}

// ReserveMP4Copy creates a seekable MP4 artifact by copying compatible
// H.264/AAC streams into a browser-friendly container.
func (service *Service) ReserveMP4Copy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultMP4CopyProfile, start)
}

// ReserveWebM creates a compatibility artifact that can be played by
// Chromium builds without proprietary H.264/MSE decoders.  It intentionally
// waits until the complete WebM file is available before exposing it, which
// gives the browser a normal seekable resource instead of a broken HLS source.
func (service *Service) ReserveWebM(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultWebMProfile, start)
}

// ReserveWebMCopy remuxes browser-native WebM streams without re-encoding.
func (service *Service) ReserveWebMCopy(input Input, start StartFunc) (Status, bool, error) {
	return service.reserve(input, DefaultWebMCopyProfile, start)
}

func (service *Service) reserve(input Input, profile string, start StartFunc) (Status, bool, error) {
	if input.UserID == 0 || input.Path == "" || input.Identity == "" || input.SourcePath == "" {
		return Status{}, false, fmt.Errorf("invalid HLS source")
	}
	if start == nil {
		return Status{}, false, fmt.Errorf("HLS task starter is required")
	}
	job := Job{
		ID:     cacheKey(input.UserID, input.Path, input.Identity, profile),
		UserID: input.UserID, Path: input.Path, Identity: input.Identity,
		SourcePath: input.SourcePath, Profile: profile,
		DurationSeconds: input.DurationSeconds,
	}

	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[job.ID]; current != nil &&
		(current.State == StateQueued || current.State == StatePreparing ||
			current.State == StateStreamable || current.State == StateCompleted) {
		return current.Status, false, nil
	}
	now := time.Now().UnixMilli()
	current := &entry{Status: Status{
		ID: job.ID, UserID: job.UserID, Path: job.Path, Identity: job.Identity,
		Profile: job.Profile, State: StateQueued, UpdatedAt: now,
		DurationSeconds: job.DurationSeconds,
	}, sourcePath: job.SourcePath}
	service.entries[job.ID] = current
	taskID, err := start(job)
	if err != nil {
		current.State = StateFailed
		current.Error = err.Error()
		current.UpdatedAt = time.Now().UnixMilli()
		return current.Status, true, err
	}
	current.TaskID = taskID
	current.UpdatedAt = time.Now().UnixMilli()
	return current.Status, true, nil
}

func (service *Service) Get(id string, userID uint) (Status, error) {
	service.mu.Lock()
	defer service.mu.Unlock()
	current := service.entries[id]
	if current == nil {
		return Status{}, ErrNotFound
	}
	if current.UserID != userID {
		return Status{}, ErrForbidden
	}
	return current.Status, nil
}

func (service *Service) Run(ctx context.Context, job Job) error {
	select {
	case service.workers <- struct{}{}:
		defer func() { <-service.workers }()
	case <-ctx.Done():
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		return ctx.Err()
	}

	service.mu.Lock()
	current := service.entries[job.ID]
	if current == nil || current.UserID != job.UserID || current.Identity != job.Identity {
		service.mu.Unlock()
		return ErrState
	}
	current.State = StatePreparing
	current.Error = ""
	current.ProcessedSeconds = 0
	current.UpdatedAt = time.Now().UnixMilli()
	service.mu.Unlock()

	directory := service.entryDir(job.ID)
	if err := os.RemoveAll(directory); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("reset HLS work directory: %w", err)
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("create HLS work directory: %w", err)
	}
	if IsWebMProfile(job.Profile) {
		return service.runWebM(ctx, job, directory)
	}
	if IsWebMCopyProfile(job.Profile) {
		return service.runWebMCopy(ctx, job, directory)
	}
	if IsMP4CopyProfile(job.Profile) {
		return service.runMP4Copy(ctx, job, directory)
	}

	playlist := filepath.Join(directory, "index.m3u8")
	segmentPattern := filepath.Join(directory, "segment-%06d.ts")
	args := ffmpegArgs(job.SourcePath, segmentPattern, playlist, ProfileMaxWidth(job.Profile))
	if IsCopyProfile(job.Profile) {
		args = copyFFmpegArgs(job.SourcePath, segmentPattern, playlist)
	}
	command := exec.CommandContext(ctx, service.ffmpegPath, args...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := command.Start(); err != nil {
		message := fmt.Sprintf("FFmpeg HLS 转码启动失败: %v", err)
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	wait := make(chan error, 1)
	go func() { wait <- command.Wait() }()
	ticker := time.NewTicker(120 * time.Millisecond)
	defer ticker.Stop()
	streamable := false
	var runErr error
	finished := false
	for !finished {
		select {
		case runErr = <-wait:
			finished = true
		case <-ticker.C:
			if !streamable && readyToStream(directory) {
				streamable = true
				service.setState(job.ID, StateStreamable, "")
			}
		case <-ctx.Done():
			runErr = <-wait
			if runErr == nil {
				runErr = ctx.Err()
			}
			finished = true
		}
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		_ = os.RemoveAll(directory)
		return ctx.Err()
	}
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		message = "FFmpeg HLS 转码失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if !readyToStream(directory) {
		message := "FFmpeg HLS 转码未生成可播放分段"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure HLS cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist HLS cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runWebM(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.webm.tmp")
	output := filepath.Join(directory, "index.webm")
	args := webMArgs(job.SourcePath, temporary)
	command := exec.CommandContext(ctx, service.ffmpegPath, args...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	stdout, err := command.StdoutPipe()
	if err != nil {
		message := fmt.Sprintf("FFmpeg WebM 进度通道创建失败: %v", err)
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := command.Start(); err != nil {
		message := fmt.Sprintf("FFmpeg WebM 转码启动失败: %v", err)
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	progressDone := make(chan struct{})
	go func() {
		defer close(progressDone)
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if seconds, ok := parseFFmpegProgressLine(scanner.Text()); ok {
				service.setProgress(job.ID, seconds)
			}
		}
	}()
	runErr := command.Wait()
	<-progressDone
	if errors.Is(ctx.Err(), context.Canceled) {
		service.finish(job.ID, StateCanceled, "任务已取消", 0)
		_ = os.RemoveAll(directory)
		return ctx.Err()
	}
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		message = "FFmpeg WebM 转码失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish WebM compatibility artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "FFmpeg WebM 转码未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}

	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure WebM cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist WebM cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runWebMCopy(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.webm.tmp")
	output := filepath.Join(directory, "index.webm")
	command := exec.CommandContext(ctx, service.ffmpegPath, webMCopyArgs(job.SourcePath, temporary)...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			service.finish(job.ID, StateCanceled, "任务已取消", 0)
			_ = os.RemoveAll(directory)
			return ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		message = "FFmpeg WebM 重新封装失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish WebM remux artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "FFmpeg WebM 重新封装未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure WebM remux cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist WebM remux metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) runMP4Copy(ctx context.Context, job Job, directory string) error {
	temporary := filepath.Join(directory, "index.mp4.tmp")
	output := filepath.Join(directory, "index.mp4")
	command := exec.CommandContext(ctx, service.ffmpegPath, mp4CopyArgs(job.SourcePath, temporary)...)
	stderr := cappedBuffer{limit: maxFFmpegError}
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			service.finish(job.ID, StateCanceled, "任务已取消", 0)
			_ = os.RemoveAll(directory)
			return ctx.Err()
		}
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		message = "MP4 兼容封装失败: " + message
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	if err := os.Rename(temporary, output); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		_ = os.RemoveAll(directory)
		return fmt.Errorf("publish MP4 remux artifact: %w", err)
	}
	if !readyForProfile(directory, job.Profile) {
		message := "MP4 兼容封装未生成可播放文件"
		service.finish(job.ID, StateFailed, message, 0)
		_ = os.RemoveAll(directory)
		return errors.New(message)
	}
	size, err := directorySize(directory)
	if err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("measure MP4 cache: %w", err)
	}
	service.finish(job.ID, StateCompleted, "", size)
	if err := service.persist(job.ID); err != nil {
		service.finish(job.ID, StateFailed, err.Error(), 0)
		return fmt.Errorf("persist MP4 cache metadata: %w", err)
	}
	service.mu.Lock()
	service.cleanupLocked(job.ID)
	service.mu.Unlock()
	return nil
}

func (service *Service) Asset(id string, userID uint, name string) (string, State, error) {
	if !assetNameRE.MatchString(name) {
		return "", "", ErrNotFound
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	current := service.entries[id]
	if current == nil {
		return "", "", ErrNotFound
	}
	if current.UserID != userID {
		return "", "", ErrForbidden
	}
	if current.State != StateStreamable && current.State != StateCompleted {
		return "", current.State, ErrState
	}
	if (name == "index.webm" || name == "index.mp4") && current.State != StateCompleted {
		return "", current.State, ErrState
	}
	path := filepath.Join(service.entryDir(id), name)
	if info, err := os.Stat(path); err != nil || info.IsDir() {
		return "", current.State, ErrNotFound
	}
	now := time.Now()
	current.LastAccessAt = now.UnixMilli()
	current.leaseUntil = now.Add(playingLease)
	return path, current.State, nil
}

func (service *Service) setState(id string, state State, message string) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[id]; current != nil {
		current.State = state
		current.Error = message
		current.UpdatedAt = time.Now().UnixMilli()
	}
}

func (service *Service) setProgress(id string, seconds float64) {
	if seconds < 0 || seconds != seconds {
		return
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[id]; current != nil && seconds >= current.ProcessedSeconds {
		current.ProcessedSeconds = seconds
		current.UpdatedAt = time.Now().UnixMilli()
	}
}

func (service *Service) finish(id string, state State, message string, size int64) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if current := service.entries[id]; current != nil {
		current.State = state
		current.Error = message
		current.SizeBytes = size
		current.UpdatedAt = time.Now().UnixMilli()
	}
}

func (service *Service) persist(id string) error {
	service.mu.Lock()
	current := service.entries[id]
	if current == nil || current.State != StateCompleted {
		service.mu.Unlock()
		return ErrState
	}
	status := current.Status
	service.mu.Unlock()

	payload, err := json.Marshal(status)
	if err != nil {
		return err
	}
	directory := service.entryDir(id)
	temporary, err := os.CreateTemp(directory, ".meta-*.tmp")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	defer func() { _ = os.Remove(temporaryName) }()
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return err
	}
	if _, err := temporary.Write(payload); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryName, filepath.Join(directory, "meta.json"))
}

func (service *Service) loadCompleted() error {
	prefixes, err := os.ReadDir(service.cacheDir)
	if err != nil {
		return fmt.Errorf("read HLS cache directory: %w", err)
	}
	for _, prefix := range prefixes {
		if !prefix.IsDir() || len(prefix.Name()) != 2 {
			continue
		}
		children, readErr := os.ReadDir(filepath.Join(service.cacheDir, prefix.Name()))
		if readErr != nil {
			return fmt.Errorf("read HLS cache prefix: %w", readErr)
		}
		for _, child := range children {
			if !child.IsDir() || !cacheIDRE.MatchString(child.Name()) || !strings.HasPrefix(child.Name(), prefix.Name()) {
				continue
			}
			directory := filepath.Join(service.cacheDir, prefix.Name(), child.Name())
			payload, readErr := os.ReadFile(filepath.Join(directory, "meta.json"))
			var status Status
			if readErr != nil || json.Unmarshal(payload, &status) != nil ||
				status.ID != child.Name() || status.State != StateCompleted || !readyForProfile(directory, status.Profile) {
				_ = os.RemoveAll(directory)
				continue
			}
			if status.SizeBytes <= 0 {
				status.SizeBytes, _ = directorySize(directory)
			}
			service.entries[status.ID] = &entry{Status: status}
		}
	}
	return nil
}

func (service *Service) cleanupLocked(protectID string) {
	total := int64(0)
	candidates := make([]*entry, 0, len(service.entries))
	now := time.Now()
	for _, current := range service.entries {
		if current.State != StateCompleted {
			continue
		}
		total += current.SizeBytes
		if current.ID != protectID && !current.leaseUntil.After(now) {
			candidates = append(candidates, current)
		}
	}
	if total <= service.maxBytes {
		return
	}
	sort.SliceStable(candidates, func(left, right int) bool {
		leftTime := candidates[left].LastAccessAt
		if leftTime == 0 {
			leftTime = candidates[left].UpdatedAt
		}
		rightTime := candidates[right].LastAccessAt
		if rightTime == 0 {
			rightTime = candidates[right].UpdatedAt
		}
		return leftTime < rightTime
	})
	for _, candidate := range candidates {
		if total <= service.maxBytes {
			break
		}
		if err := os.RemoveAll(service.entryDir(candidate.ID)); err != nil {
			continue
		}
		total -= candidate.SizeBytes
		delete(service.entries, candidate.ID)
	}
}

func (service *Service) entryDir(id string) string {
	return filepath.Join(service.cacheDir, id[:2], id)
}

func cacheKey(userID uint, path, identity, profile string) string {
	digest := sha256.Sum256([]byte(strconv.FormatUint(uint64(userID), 10) + "\x00" + path + "\x00" + identity + "\x00" + profile))
	return hex.EncodeToString(digest[:])
}

func ffmpegArgs(source, segmentPattern, playlist string, maxWidth int) []string {
	if maxWidth <= 0 {
		maxWidth = 1280
	}
	scale := fmt.Sprintf("scale=w='trunc(min(%d,iw)/2)*2':h=-2:force_original_aspect_ratio=decrease,pad=ceil(iw/2)*2:ceil(ih/2)*2", maxWidth)
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-vf", scale,
		"-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p",
		"-threads", "1", "-filter_threads", "1",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2",
		"-force_key_frames", "expr:gte(t,n_forced*4)",
		"-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", segmentPattern, playlist,
	}
}

func copyFFmpegArgs(source, segmentPattern, playlist string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-c:v", "copy", "-c:a", "copy",
		"-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
		"-hls_flags", "independent_segments+temp_file",
		"-hls_segment_filename", segmentPattern, playlist,
	}
}

func webMArgs(source, output string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?",
		"-vf", "scale=w='trunc(min(1280,iw)/2)*2':h=-2:force_original_aspect_ratio=decrease,pad=ceil(iw/2)*2:ceil(ih/2)*2",
		"-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1.5M",
		// Keep the compatibility queue globally serial, but let the single
		// active VP9 encode use two codec threads. On the NAS ARM host this
		// materially shortens the first-play wait without increasing the
		// number of simultaneous FFmpeg jobs.
		"-row-mt", "1", "-threads", "2", "-pix_fmt", "yuv420p",
		"-c:a", "libopus", "-b:a", "128k", "-ac", "2",
		"-progress", "pipe:1",
		"-f", "webm", output,
	}
}

func webMCopyArgs(source, output string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-f", "webm", output,
	}
}

func mp4CopyArgs(source, output string) []string {
	return []string{
		"-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
		"-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart",
		"-f", "mp4", output,
	}
}

func parseFFmpegProgressLine(line string) (float64, bool) {
	key, value, found := strings.Cut(strings.TrimSpace(line), "=")
	if !found || key != "out_time_ms" {
		return 0, false
	}
	microseconds, err := strconv.ParseInt(value, 10, 64)
	if err != nil || microseconds < 0 {
		return 0, false
	}
	return float64(microseconds) / 1_000_000, true
}

func readyToStream(directory string) bool {
	playlist, err := os.Stat(filepath.Join(directory, "index.m3u8"))
	if err != nil || playlist.Size() == 0 {
		return false
	}
	segment, err := os.Stat(filepath.Join(directory, "segment-000000.ts"))
	return err == nil && segment.Size() > 0
}

func readyForProfile(directory, profile string) bool {
	if IsWebMProfile(profile) || IsWebMCopyProfile(profile) {
		info, err := os.Stat(filepath.Join(directory, "index.webm"))
		return err == nil && !info.IsDir() && info.Size() > 0
	}
	if IsMP4CopyProfile(profile) {
		info, err := os.Stat(filepath.Join(directory, "index.mp4"))
		return err == nil && !info.IsDir() && info.Size() > 0
	}
	return readyToStream(directory)
}

func directorySize(directory string) (int64, error) {
	var total int64
	err := filepath.WalkDir(directory, func(_ string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		total += info.Size()
		return nil
	})
	return total, err
}
