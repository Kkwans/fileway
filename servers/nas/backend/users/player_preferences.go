package users

import (
	"math"
	"strings"
)

// PlayerPreferences holds cross-device media player settings.
type PlayerPreferences struct {
	// ControlsTimeoutSec: nil = default 4s; 0 = never auto-hide; 1-20 = seconds.
	ControlsTimeoutSec *int `json:"controlsTimeoutSec,omitempty"`
	// PlaybackMode: nil/empty/"native" | "compat" | "ask".
	PlaybackMode string `json:"playbackMode,omitempty"`
	// PlaybackRate: nil = 1; 0.1–5.0 custom rate.
	PlaybackRate *float64 `json:"playbackRate,omitempty"`
	// ResumeMode: resume (default) | from-start | ask.
	ResumeMode string `json:"resumeMode,omitempty"`
	// ResumeMinSec: history threshold for resume UI; default 10; clamp 5–600.
	ResumeMinSec *int `json:"resumeMinSec,omitempty"`
}

// ResolvePlaybackMode returns native|compat|ask.
func ResolvePlaybackMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "compat", "compatible", "hls":
		return "compat"
	case "ask", "choose", "select":
		return "ask"
	default:
		return "native"
	}
}

// ResolveResumeMode returns resume|from-start|ask.
func ResolveResumeMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "from-start", "start", "restart", "none":
		return "from-start"
	case "ask", "prompt", "choose":
		return "ask"
	default:
		return "resume"
	}
}

// ResolvePlaybackRate returns 0.1–5.0; default 1.
func ResolvePlaybackRate(rate *float64) float64 {
	if rate == nil {
		return 1
	}
	v := *rate
	if math.IsNaN(v) || math.IsInf(v, 0) || v < 0.1 || v > 5 {
		return 1
	}
	// two decimal places
	return float64(int(v*100+0.5)) / 100
}

// ResolveResumeMinSec returns history threshold seconds; default 10; clamp 5–600.
func ResolveResumeMinSec(sec *int) int {
	const defaultSec = 10
	const minSec = 5
	const maxSec = 600
	if sec == nil {
		return defaultSec
	}
	v := *sec
	if v < minSec {
		return minSec
	}
	if v > maxSec {
		return maxSec
	}
	return v
}

// ResolveControlsTimeoutSec returns seconds for video.js inactivityTimeout.
// 0 means never auto-hide.
func ResolveControlsTimeoutSec(sec *int) int {
	if sec == nil {
		return 4
	}
	v := *sec
	if v < 0 || v > 20 {
		return 4
	}
	return v
}

func (p PlayerPreferences) Merge(next PlayerPreferences) PlayerPreferences {
	if next.ControlsTimeoutSec != nil {
		p.ControlsTimeoutSec = next.ControlsTimeoutSec
	}
	if next.PlaybackMode != "" {
		p.PlaybackMode = next.PlaybackMode
	}
	if next.PlaybackRate != nil {
		p.PlaybackRate = next.PlaybackRate
	}
	if next.ResumeMode != "" {
		p.ResumeMode = next.ResumeMode
	}
	if next.ResumeMinSec != nil {
		p.ResumeMinSec = next.ResumeMinSec
	}
	return p
}
func (p *PlayerPreferences) Normalize() {
	if p.ControlsTimeoutSec != nil {
		v := ResolveControlsTimeoutSec(p.ControlsTimeoutSec)
		p.ControlsTimeoutSec = &v
	}
	if p.PlaybackRate != nil {
		v := ResolvePlaybackRate(p.PlaybackRate)
		p.PlaybackRate = &v
	}
	if p.ResumeMinSec != nil {
		v := ResolveResumeMinSec(p.ResumeMinSec)
		p.ResumeMinSec = &v
	}
	p.PlaybackMode = ResolvePlaybackMode(p.PlaybackMode)
	p.ResumeMode = ResolveResumeMode(p.ResumeMode)
}
