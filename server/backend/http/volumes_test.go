package fbhttp

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/risk"
)

func TestDiscoverVolumesUsesConfiguredRootAndVirtualPaths(t *testing.T) {
	t.Parallel()

	serverRoot := t.TempDir()
	for _, path := range []string{
		filepath.Join(serverRoot, "volume1", "Project"),
		filepath.Join(serverRoot, "volumeUSB2", "Photos"),
		filepath.Join(serverRoot, "etc"),
	} {
		if err := os.MkdirAll(path, 0o750); err != nil {
			t.Fatal(err)
		}
	}

	volumes, err := discoverVolumes(context.Background(), serverRoot)
	if err != nil {
		t.Fatal(err)
	}
	if len(volumes) != 2 {
		t.Fatalf("discoverVolumes() returned %d entries: %#v", len(volumes), volumes)
	}

	byPath := make(map[string]Volume, len(volumes))
	for _, volume := range volumes {
		byPath[volume.Path] = volume
	}

	primary, ok := byPath["/volume1"]
	if !ok {
		t.Fatalf("missing virtual /volume1 path: %#v", volumes)
	}
	if primary.Name != "存储卷 1" || primary.Type != "system" {
		t.Fatalf("primary volume = %#v", primary)
	}
	if !reflect.DeepEqual(primary.SubDirs, []SubDir{{Path: "/volume1/Project", Name: "Project", Risk: risk.Low}}) {
		t.Fatalf("primary subdirectories = %#v", primary.SubDirs)
	}

	usb, ok := byPath["/volumeUSB2"]
	if !ok {
		t.Fatalf("missing virtual /volumeUSB2 path: %#v", volumes)
	}
	if usb.Name != "USB 存储 2" || usb.Type != "usb" {
		t.Fatalf("USB volume = %#v", usb)
	}
}

func TestDiscoverVolumesReportsConfiguredRootFailure(t *testing.T) {
	t.Parallel()

	_, err := discoverVolumes(context.Background(), filepath.Join(t.TempDir(), "missing"))
	if err == nil {
		t.Fatal("discoverVolumes() error = nil, want configured root error")
	}
}
