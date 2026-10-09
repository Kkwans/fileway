package fbhttp

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

// decodeResourceWirePath decodes each path segment once, preserving arbitrary
// filesystem bytes and literal '+'. It does not authorize access to that path.
func decodeResourceWirePath(raw string) (string, error) {
	if !strings.HasPrefix(raw, "/") || strings.HasPrefix(raw, "//") || strings.ContainsAny(raw, "?#") {
		return "", fmt.Errorf("原始路径无效")
	}
	for _, value := range []byte(raw) {
		if value < 0x21 || value > 0x7e {
			return "", fmt.Errorf("原始路径必须使用百分号编码")
		}
	}
	parts := strings.Split(raw, "/")
	for index, part := range parts {
		decoded, err := url.PathUnescape(part)
		if err != nil || strings.ContainsAny(decoded, "/\x00") {
			return "", fmt.Errorf("原始路径编码无效")
		}
		parts[index] = decoded
	}
	return pathmeta.Clean(strings.Join(parts, "/")), nil
}
