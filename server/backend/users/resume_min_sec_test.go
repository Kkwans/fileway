package users

import "testing"

func TestResolveResumeMinSec(t *testing.T) {
	if got := ResolveResumeMinSec(nil); got != 10 {
		t.Fatalf("nil default want 10 got %d", got)
	}
	three := 3
	if got := ResolveResumeMinSec(&three); got != 5 {
		t.Fatalf("clamp low want 5 got %d", got)
	}
	ok := 30
	if got := ResolveResumeMinSec(&ok); got != 30 {
		t.Fatalf("want 30 got %d", got)
	}
	big := 601
	if got := ResolveResumeMinSec(&big); got != 600 {
		t.Fatalf("clamp high want 600 got %d", got)
	}
}
