package hls

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"math"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Progress describes media time, not bytes. UpdatedAt is the last FFmpeg
// heartbeat; AdvancedAt changes only when the output timeline advances.
type Progress struct {
	Phase            string  `json:"phase"`
	DurationSeconds  float64 `json:"durationSeconds"`
	ProcessedSeconds float64 `json:"processedSeconds"`
	Speed            float64 `json:"speed"`
	FPS              float64 `json:"fps"`
	StartedAt        int64   `json:"startedAt,omitempty"`
	UpdatedAt        int64   `json:"updatedAt"`
	AdvancedAt       int64   `json:"advancedAt,omitempty"`
	PlayableSeconds  float64 `json:"playableSeconds,omitempty"`
	Method           string  `json:"method,omitempty"`
}

func (service *Service) RunWithProgress(ctx context.Context, job Job, report func(Progress) error) error {
	job.report = report
	err := service.Run(ctx, job)
	if err != nil {
		state := StateFailed
		if ctx.Err() != nil {
			state = StateCanceled
		}
		service.finish(job.ID, state, err.Error(), 0)
	}
	return err
}

func (service *Service) reportPhase(job Job, phase string) error {
	p := Progress{Phase: phase, DurationSeconds: job.DurationSeconds, UpdatedAt: time.Now().UnixMilli()}
	if phase != "queued" {
		p.StartedAt = p.UpdatedAt
		p.AdvancedAt = p.UpdatedAt
	}
	return service.publishProgress(job, p)
}

func (service *Service) publishProgress(job Job, p Progress) error {
	service.mu.Lock()
	if current := service.entries[job.ID]; current != nil {
		current.Progress = &p
		current.ProcessedSeconds = p.ProcessedSeconds
		current.UpdatedAt = p.UpdatedAt
	}
	service.mu.Unlock()
	if job.report != nil {
		return job.report(p)
	}
	return nil
}

// Start stdout consumption before waiting on FFmpeg. StdoutPipe must be
// drained before Wait closes the pipe, including for very fast remux jobs.
func (service *Service) startProgress(command *exec.Cmd, job Job) (<-chan error, error) {
	command.Args = softwareThreadArgs(command.Args, service.encodeThreads)
	var method string
	command.Args, method = acceleratorArgs(command.Args, job, service.accelerator)
	command.Args = playbackWindowArgs(command.Args, job)
	if !containsProgressArg(command.Args) {
		command.Args = append([]string{command.Args[0], "-progress", "pipe:1", "-stats_period", "1"}, command.Args[1:]...)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := command.Start(); err != nil {
		return nil, err
	}
	done := make(chan error, 1)
	go func() {
		err := readProgress(stdout, job.DurationSeconds, func(p Progress) error { p.Method = method; return service.publishProgress(job, p) })
		if err != nil {
			_ = command.Process.Kill()
		}
		done <- err
	}()
	return done, nil
}

// Bound both decoding and encoding concurrency. Stream-copy jobs retain their
// fast path; worker channels still bound the number of simultaneous jobs.
func softwareThreadArgs(args []string, threads int) []string {
	if threads <= 0 {
		threads = 1
	}
	encode := false
	for i := 1; i+1 < len(args); i++ {
		if args[i] == "-c:v" && (args[i+1] == "libx264" || args[i+1] == "libvpx-vp9") {
			encode = true
		}
	}
	if !encode {
		return args
	}
	result := make([]string, 0, len(args)+2)
	for i := 0; i < len(args); i++ {
		if args[i] == "-i" {
			result = append(result, "-threads", strconv.Itoa(threads))
		}
		result = append(result, args[i])
		if (args[i] == "-threads" || args[i] == "-filter_threads" || args[i] == "-filter_complex_threads") && i+1 < len(args) {
			result = append(result, strconv.Itoa(threads))
			i++
		}
	}
	return result
}

func playbackWindowArgs(args []string, job Job) []string {
	if job.WindowSeconds <= 0 {
		return args
	}
	result := make([]string, 0, len(args)+4)
	for _, arg := range args {
		if arg == "-i" {
			result = append(result, "-ss", strconv.FormatFloat(job.StartSeconds, 'f', 3, 64))
		}
		result = append(result, arg)
	}
	output := result[len(result)-1]
	result = result[:len(result)-1]
	return append(result, "-t", strconv.FormatFloat(job.DurationSeconds, 'f', 3, 64), output)
}

func (service *Service) runProgress(command *exec.Cmd, job Job) error {
	done, err := service.startProgress(command, job)
	if err != nil {
		return err
	}
	progressErr := <-done
	err = command.Wait()
	if progressErr != nil {
		return fmt.Errorf("读取转码进度失败: %w", progressErr)
	}
	return err
}

func containsProgressArg(args []string) bool {
	for _, arg := range args {
		if arg == "-progress" {
			return true
		}
	}
	return false
}

func progressNumber(value string) float64 {
	n, err := strconv.ParseFloat(strings.TrimSuffix(strings.TrimSpace(value), "x"), 64)
	if err != nil || math.IsNaN(n) || math.IsInf(n, 0) || n < 0 {
		return 0
	}
	return n
}

func readProgress(reader io.Reader, duration float64, report func(Progress) error) error {
	now := time.Now().UnixMilli()
	p := Progress{Phase: "encoding", DurationSeconds: duration, StartedAt: now, AdvancedAt: now}
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), "=")
		if !ok {
			continue
		}
		switch key {
		case "out_time_us", "out_time_ms": // Both fields use microseconds.
			seconds := progressNumber(value) / 1_000_000
			if duration > 0 {
				seconds = math.Min(seconds, duration)
			}
			if seconds > p.ProcessedSeconds {
				p.ProcessedSeconds = seconds
				p.AdvancedAt = time.Now().UnixMilli()
			}
		case "speed":
			p.Speed = progressNumber(value)
		case "fps":
			p.FPS = progressNumber(value)
		case "progress":
			p.UpdatedAt = time.Now().UnixMilli()
			if value == "end" {
				p.Phase = "finalizing"
			}
			if err := report(p); err != nil {
				return err
			}
		}
	}
	return scanner.Err()
}
