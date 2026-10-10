package files

import (
	"github.com/spf13/afero"
	"strings"
	"testing"
)

func TestPrivateResourceNamespaceKeepsLexicalAndOrdinaryPathsDistinct(t *testing.T) {
	fs := afero.NewMemMapFs()
	private := UploadPartPrefix + strings.Repeat("a", 32) + ".part"
	for _, name := range []string{"/" + private, "/folder/" + private + "/old/file.txt", "/" + strings.ToUpper(private) + "/new"} {
		if !IsPrivateResourcePath(fs, name) {
			t.Fatalf("private resource exposed: %q", name)
		}
	}
	for _, name := range []string{"/ordinary.txt", "/.fileway-upload-personal.part", "/100%2F.txt", "/合法�.txt", "/\xff-name"} {
		if IsPrivateResourcePath(fs, name) {
			t.Fatalf("ordinary filename reinterpreted: %q", name)
		}
	}
}
