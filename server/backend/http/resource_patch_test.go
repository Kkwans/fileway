package fbhttp

import (
	"net/http/httptest"
	"testing"
)

func TestPatchDestinationIsDecodedExactlyOnce(t *testing.T) {
	request := httptest.NewRequest("PATCH", "/api/resources/source?destination=%252Fname", nil)
	decodedByNetURL := request.URL.Query().Get("destination")
	if got := normalizeResourcePath(decodedByNetURL); got != "/%2Fname" {
		t.Fatalf("normalized destination = %q, want literal encoded filename", got)
	}
}

func TestSameExistingFileHandlesMissingDestination(t *testing.T) {
	if sameExistingFile(nil, nil) {
		t.Fatal("missing files must not compare as the same file")
	}
}
