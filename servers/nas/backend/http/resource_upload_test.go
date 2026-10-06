package fbhttp

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/spf13/afero"
)

func TestWriteFileExclusiveNeverOverwritesExistingFile(t *testing.T) {
	filesystem := afero.NewOsFs()
	target := filepath.Join(t.TempDir(), "assets", "photo.png")
	if err := filesystem.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := afero.WriteFile(filesystem, target, []byte("original"), 0o640); err != nil {
		t.Fatal(err)
	}

	_, err := writeFileExclusive(filesystem, target, strings.NewReader("replacement"), 0o640, 0o750)
	if !errors.Is(err, os.ErrExist) {
		t.Fatalf("exclusive write error = %v, want os.ErrExist", err)
	}
	content, err := afero.ReadFile(filesystem, target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "original" {
		t.Fatalf("existing file changed to %q", content)
	}
}

func TestConcurrentExclusiveWritesCreateExactlyOneFile(t *testing.T) {
	filesystem := afero.NewOsFs()
	target := filepath.Join(t.TempDir(), "assets", "photo.png")
	start := make(chan struct{})
	errorsByWriter := make(chan error, 2)
	var writers sync.WaitGroup

	for _, content := range []string{"first", "second"} {
		content := content
		writers.Add(1)
		go func() {
			defer writers.Done()
			<-start
			_, err := writeFileExclusive(filesystem, target, strings.NewReader(content), 0o640, 0o750)
			errorsByWriter <- err
		}()
	}
	close(start)
	writers.Wait()
	close(errorsByWriter)

	succeeded := 0
	conflicted := 0
	for err := range errorsByWriter {
		switch {
		case err == nil:
			succeeded++
		case errors.Is(err, os.ErrExist):
			conflicted++
		default:
			t.Fatalf("unexpected exclusive write error: %v", err)
		}
	}
	if succeeded != 1 || conflicted != 1 {
		t.Fatalf("exclusive results: succeeded=%d conflicted=%d", succeeded, conflicted)
	}

	content, err := afero.ReadFile(filesystem, target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "first" && string(content) != "second" {
		t.Fatalf("unexpected stored content %q", content)
	}
}
