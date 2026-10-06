package fbhttp

import (
	"os"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/internal/testutil"
)

func TestMain(tests *testing.M) {
	testutil.RunFFmpegHelper()
	os.Exit(tests.Run())
}
