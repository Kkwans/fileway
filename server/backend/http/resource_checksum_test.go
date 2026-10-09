package fbhttp

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/users"
	"github.com/spf13/afero"
)

func TestResourceChecksumRequiresContentPermissionAndWorksWithMetadata(t *testing.T) {
	for _, allowed := range []bool{false, true} {
		h := newTrashHTTPHarness(t, users.User{Username: "owner", Perm: users.Permissions{Download: allowed}})
		owner := firstTrashHTTPUser(h)
		content := []byte("explicit-checksum")
		if err := afero.WriteFile(h.fs[owner.ID], "/owned.txt", content, 0o600); err != nil {
			t.Fatal(err)
		}
		response := h.request(t, owner.ID, resourceGetHandler, http.MethodGet, "/owned.txt?metadata=1&checksum=sha256", nil, nil)
		if !allowed {
			if response.Code != http.StatusForbidden {
				t.Fatalf("no download permission: %d %s", response.Code, response.Body.String())
			}
			continue
		}
		var body struct {
			WirePath  string            `json:"wirePath"`
			Content   string            `json:"content"`
			Checksums map[string]string `json:"checksums"`
		}
		expected := sha256.Sum256(content)
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &body) != nil || body.WirePath != "/owned.txt" || body.Content != "" || body.Checksums["sha256"] != hex.EncodeToString(expected[:]) {
			t.Fatalf("metadata/checksum result: %d %s", response.Code, response.Body.String())
		}
		for _, target := range []string{"/owned.txt?checksum=unsupported", "/?checksum=sha256"} {
			response = h.request(t, owner.ID, resourceGetHandler, http.MethodGet, target, nil, nil)
			if response.Code != http.StatusBadRequest {
				t.Fatalf("invalid checksum target: %d %s", response.Code, response.Body.String())
			}
		}
	}
}
