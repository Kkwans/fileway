package files

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/spf13/afero"
)

func TestPrivateUploadPartCaseAliasesAreReserved(t *testing.T) {
	name := UploadPartPrefix + strings.Repeat("a", 32) + ".part"
	for _, value := range []string{"/folder/" + name, "/folder/" + strings.ToUpper(name), `C:\folder\` + strings.ToUpper(name)} {
		if !IsUploadPartPath(value) {
			t.Fatalf("private upload alias exposed: %q", value)
		}
	}
	if IsUploadPartPath("/.fileway-upload-personal.part") {
		t.Fatal("ordinary filename was reserved")
	}
}

func TestNativeUploadPublicationNeverOverwritesConcurrentTarget(t *testing.T) {
	root := t.TempDir()
	fs := afero.NewBasePathFs(afero.NewOsFs(), root)
	for _, name := range []string{"/one.part", "/two.part"} {
		if err := afero.WriteFile(fs, name, []byte(name), 0600); err != nil {
			t.Fatal(err)
		}
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	var group sync.WaitGroup
	for _, name := range []string{"/one.part", "/two.part"} {
		group.Add(1)
		go func(value string) {
			defer group.Done()
			<-start
			results <- PublishUpload(fs, value, "/result.bin", false)
		}(name)
	}
	close(start)
	group.Wait()
	close(results)
	accepted, conflicts := 0, 0
	for err := range results {
		if err == nil {
			accepted++
		} else if errors.Is(err, os.ErrExist) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if accepted != 1 || conflicts != 1 {
		t.Fatalf("publication results accepted=%d conflicts=%d", accepted, conflicts)
	}
	value, err := os.ReadFile(filepath.Join(root, "result.bin"))
	if err != nil || string(value) != "/one.part" && string(value) != "/two.part" {
		t.Fatal(string(value), err)
	}
}

func TestApprovedUploadReplacementPublishesOnlyCompletedPart(t *testing.T) {
	fs := afero.NewBasePathFs(afero.NewOsFs(), t.TempDir())
	if err := afero.WriteFile(fs, "/original.bin", []byte("original"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(fs, "/complete.part", []byte("complete"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := PublishUpload(fs, "/complete.part", "/original.bin", false); !errors.Is(err, os.ErrExist) {
		t.Fatal(err)
	}
	value, _ := afero.ReadFile(fs, "/original.bin")
	if string(value) != "original" {
		t.Fatal("original changed before approval")
	}
	if err := PublishUpload(fs, "/complete.part", "/original.bin", true); err != nil {
		t.Fatal(err)
	}
	value, _ = afero.ReadFile(fs, "/original.bin")
	if string(value) != "complete" {
		t.Fatal(string(value))
	}
}
