package files

import (
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/risk"
	"github.com/spf13/afero"
)

type allowAllRiskTestPaths struct{}

func (allowAllRiskTestPaths) Check(string) bool { return true }

func TestStatAddsBackendRiskLevel(t *testing.T) {
	t.Parallel()

	fs := afero.NewMemMapFs()
	if err := fs.MkdirAll("/etc", 0o755); err != nil {
		t.Fatal(err)
	}
	file, err := stat(&FileOptions{Fs: fs, Path: "/etc"})
	if err != nil {
		t.Fatal(err)
	}
	if file.RiskLevel != risk.High {
		t.Fatalf("riskLevel = %q, want %q", file.RiskLevel, risk.High)
	}
}

func TestExpandedListingAddsRiskLevelToEveryChild(t *testing.T) {
	t.Parallel()

	fs := afero.NewMemMapFs()
	for _, directory := range []string{"/etc", "/etc2", "/volume1/@docker"} {
		if err := fs.MkdirAll(directory, 0o755); err != nil {
			t.Fatal(err)
		}
	}

	root, err := NewFileInfo(&FileOptions{
		Fs:      fs,
		Path:    "/",
		Expand:  true,
		Checker: allowAllRiskTestPaths{},
	})
	if err != nil {
		t.Fatal(err)
	}

	got := make(map[string]risk.Level, len(root.Items))
	for _, item := range root.Items {
		got[item.Path] = item.RiskLevel
	}
	if got["/etc"] != risk.High {
		t.Fatalf("/etc riskLevel = %q, want %q", got["/etc"], risk.High)
	}
	if got["/etc2"] != risk.Low {
		t.Fatalf("/etc2 riskLevel = %q, want %q", got["/etc2"], risk.Low)
	}
}
