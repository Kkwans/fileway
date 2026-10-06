package users

import (
	"path/filepath"
	"strings"

	"github.com/spf13/afero"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/rules"
)

// ViewMode describes a view mode.
type ViewMode string

const (
	ListViewMode   ViewMode = "list"
	MosaicViewMode ViewMode = "mosaic"
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
	if v < 0.1 || v > 5 {
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

// User describes a user.
type User struct {
	ID                    uint                `storm:"id,increment" json:"id"`
	Username              string              `storm:"unique" json:"username"`
	Password              string              `json:"password"`
	Scope                 string              `json:"scope"`
	Locale                string              `json:"locale"`
	LockPassword          bool                `json:"lockPassword"`
	ViewMode              ViewMode            `json:"viewMode"`
	SingleClick           bool                `json:"singleClick"`
	RedirectAfterCopyMove bool                `json:"redirectAfterCopyMove"`
	Perm                  Permissions         `json:"perm"`
	Commands              []string            `json:"commands"`
	Sorting               files.Sorting       `json:"sorting"`
	Fs                    afero.Fs            `json:"-" yaml:"-"`
	Rules                 []rules.Rule        `json:"rules"`
	HideDotfiles          bool                `json:"hideDotfiles"`
	DateFormat            bool                `json:"dateFormat"`
	AceEditorTheme        string              `json:"aceEditorTheme"`
	SidebarPreferences    string              `json:"sidebarPreferences"`
	ListingPreferences    ListingPreferences  `json:"listingPreferences"`
	PlayerPreferences     PlayerPreferences   `json:"playerPreferences"`
}

// GetRules implements rules.Provider.
func (u *User) GetRules() []rules.Rule {
	return u.Rules
}

var checkableFields = []string{
	"Username",
	"Password",
	"Scope",
	"ViewMode",
	"Commands",
	"Sorting",
	"Rules",
	"ListingPreferences",
	"PlayerPreferences",
}

// Clean cleans up a user and verifies if all its fields
// are alright to be saved.
func (u *User) Clean(baseScope string, fields ...string) error {
	if len(fields) == 0 {
		fields = checkableFields
	}

	for _, field := range fields {
		switch field {
		case "Username":
			if u.Username == "" {
				return fberrors.ErrEmptyUsername
			}
		case "Password":
			if u.Password == "" {
				return fberrors.ErrEmptyPassword
			}
		case "ViewMode":
			if u.ViewMode == "" {
				u.ViewMode = ListViewMode
			}
		case "Commands":
			if u.Commands == nil {
				u.Commands = []string{}
			}
		case "Sorting":
			if u.Sorting.By == "" {
				u.Sorting.By = "name"
			}
			// Explorer-like: default new/empty sorting to name ascending.
			// Bolt users saved before this fork may still have Asc=false in
			// stored data; frontend computer-root listing forces C→D anyway.
			if u.Sorting.By == "name" && !u.Sorting.Asc {
				// Only flip when the row looks like the old inverted default
				// (By set to name with zero-ish desc from quickSetup history).
				// Explicit user desc preference is indistinguishable in storage;
				// prefer Explorer order for this Windows fork.
				u.Sorting.Asc = true
			}
		case "Rules":
			if u.Rules == nil {
				u.Rules = []rules.Rule{}
			}
		case "ListingPreferences":
			preferences, err := NormalizeListingPreferences(u.ListingPreferences, u.HideDotfiles)
			if err != nil {
				return err
			}
			u.ListingPreferences = preferences
		case "PlayerPreferences":
			if u.PlayerPreferences.ControlsTimeoutSec != nil {
				v := *u.PlayerPreferences.ControlsTimeoutSec
				if v < 0 || v > 20 {
					clamped := ResolveControlsTimeoutSec(&v)
					u.PlayerPreferences.ControlsTimeoutSec = &clamped
				}
			}
			u.PlayerPreferences.PlaybackMode = ResolvePlaybackMode(u.PlayerPreferences.PlaybackMode)
			u.PlayerPreferences.ResumeMode = ResolveResumeMode(u.PlayerPreferences.ResumeMode)
			if u.PlayerPreferences.PlaybackRate != nil {
				v := ResolvePlaybackRate(u.PlayerPreferences.PlaybackRate)
				u.PlayerPreferences.PlaybackRate = &v
			}
			// Only clamp when client sent a value — do not force-materialize defaults.
			if u.PlayerPreferences.ResumeMinSec != nil {
				v := ResolveResumeMinSec(u.PlayerPreferences.ResumeMinSec)
				u.PlayerPreferences.ResumeMinSec = &v
			}
		}
	}

	if u.Fs == nil {
		if files.UseDriveFs(baseScope, u.Scope) {
			u.Fs = files.NewDriveFs()
			if strings.TrimSpace(u.Scope) == "" {
				u.Scope = "/"
			}
			return nil
		}
		scope := u.Scope
		scope = filepath.Join(baseScope, filepath.Join("/", scope))
		u.Fs = afero.NewBasePathFs(afero.NewOsFs(), scope)
	}

	return nil
}

// FullPath gets the full path for a user's relative path.
func (u *User) FullPath(path string) string {
	switch fs := u.Fs.(type) {
	case *files.DriveFs:
		real, err := fs.RealPath(path)
		if err != nil {
			// Virtual root and non-drive paths have no native cwd.
			return ""
		}
		return real
	case *afero.BasePathFs:
		return afero.FullBaseFsPath(fs, path)
	default:
		return path
	}
}
