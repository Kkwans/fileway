package hls

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"testing"
	"time"

	"github.com/Kkwans/nas-file-browser/backend/internal/testutil"
)

func TestMain(tests *testing.M) {
	if real := os.Getenv("FILEWAY_TEST_PACED_EXPORT"); real != "" {
		os.Exit(runPacedExport(real))
	}
	testutil.RunFFmpegHelper()
	os.Exit(tests.Run())
}

// The test binary is a portable argv adapter, not a fake encoder. All media
// bytes and progress come from the installed FFmpeg, paced at input speed.
func runPacedExport(real string) int {
	args := append([]string{"-re"}, os.Args[1:]...)
	receipt, err := os.OpenFile(os.Getenv("FILEWAY_TEST_PACED_EXPORT_RECEIPT"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	err = json.NewEncoder(receipt).Encode(args)
	closeErr := receipt.Close()
	if err != nil || closeErr != nil {
		fmt.Fprintln(os.Stderr, err, closeErr)
		return 1
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, real, args...)
	command.Stdin, command.Stdout, command.Stderr = os.Stdin, os.Stdout, os.Stderr
	err = command.Run()
	if markerErr := os.WriteFile(os.Getenv("FILEWAY_TEST_PACED_EXPORT_DONE"), []byte("finished"), 0o600); markerErr != nil {
		fmt.Fprintln(os.Stderr, markerErr)
		return 1
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			return exit.ExitCode()
		}
		return 1
	}
	return 0
}
