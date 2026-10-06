package users

import (
	"encoding/json"
	"math"
	"testing"
)

func TestPlayerPreferencesPartialMerge(t *testing.T) {
	var old, patch PlayerPreferences
	if err := json.Unmarshal([]byte(`{"controlsTimeoutSec":0,"playbackRate":1.5,"resumeMinSec":20,"resumeMode":"ask"}`), &old); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(`{"playbackMode":"compat"}`), &patch); err != nil {
		t.Fatal(err)
	}
	merged := old.Merge(patch)
	merged.Normalize()
	if *merged.ControlsTimeoutSec != 0 || *merged.PlaybackRate != 1.5 || *merged.ResumeMinSec != 20 || merged.ResumeMode != "ask" || merged.PlaybackMode != "compat" {
		t.Fatalf("lost preferences: %+v", merged)
	}
}
func TestPlayerPreferencesDefaultsAndBounds(t *testing.T) {
	var p PlayerPreferences
	p.Normalize()
	if p.PlaybackMode != "native" || p.ResumeMode != "resume" || ResolveResumeMinSec(p.ResumeMinSec) != 10 || ResolveControlsTimeoutSec(p.ControlsTimeoutSec) != 4 {
		t.Fatalf("defaults: %+v", p)
	}
	for _, value := range []float64{math.NaN(), math.Inf(1), -1, 6} {
		if ResolvePlaybackRate(&value) != 1 {
			t.Fatal(value)
		}
	}
	low, high := 1, 999
	if ResolveResumeMinSec(&low) != 5 || ResolveResumeMinSec(&high) != 600 {
		t.Fatal("resume bounds")
	}
}

type preferenceBackend struct {
	StorageBackend
	user User
}

func (b *preferenceBackend) GetBy(_ interface{}) (*User, error) { u := b.user; return &u, nil }
func (b *preferenceBackend) Update(u *User, fields ...string) error {
	for _, field := range fields {
		if field == "PlayerPreferences" {
			b.user.PlayerPreferences = u.PlayerPreferences
		}
	}
	return nil
}
func TestStorageMergesPlayerPreferencesBeforeNormalizing(t *testing.T) {
	rate := 1.75
	back := &preferenceBackend{user: User{ID: 1, PlayerPreferences: PlayerPreferences{PlaybackRate: &rate, ResumeMode: "ask"}}}
	store := NewStorage(back)
	if err := store.Update(&User{ID: 1, PlayerPreferences: PlayerPreferences{PlaybackMode: "compat"}}, "PlayerPreferences"); err != nil {
		t.Fatal(err)
	}
	if *back.user.PlayerPreferences.PlaybackRate != rate || back.user.PlayerPreferences.ResumeMode != "ask" || back.user.PlayerPreferences.PlaybackMode != "compat" {
		t.Fatal("partial update lost persisted values")
	}
}
