package fbhttp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestOrdinaryLinuxRootDoesNotRequireNASVolumeNames(t *testing.T) {
	root := t.TempDir()
	volumes, err := discoverVolumes(context.Background(), root)
	if err != nil {
		t.Fatal(err)
	}
	if len(volumes) != 1 || volumes[0].Path != "/" || volumes[0].Name == "" {
		t.Fatalf("scoped filesystem volumes: %+v", volumes)
	}
}

func TestNASVolumePathsStayVirtualWithinConfiguredRoot(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "volume2", "Documents"), 0o700); err != nil {
		t.Fatal(err)
	}
	volumes, err := discoverVolumes(context.Background(), root)
	if err != nil {
		t.Fatal(err)
	}
	if len(volumes) != 1 || volumes[0].Path != "/volume2" {
		t.Fatalf("volume schema/path changed: %+v", volumes)
	}
	for _, directory := range volumes[0].SubDirs {
		if directory.Path == "/volume2/Documents" {
			return
		}
	}
	t.Fatal("scoped Documents path missing")
}
