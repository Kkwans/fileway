package fbhttp

import (
	"strings"
	"testing"
)

func TestAppContentSecurityPolicyAllowsSameOriginMediaAndMSEBlob(t *testing.T) {
	if !strings.Contains(appContentSecurityPolicy, "media-src 'self' blob:") {
		t.Fatalf("CSP does not allow same-origin media and Video.js MSE blobs: %s", appContentSecurityPolicy)
	}
	if strings.Contains(appContentSecurityPolicy, "media-src *") || strings.Contains(appContentSecurityPolicy, "media-src http:") || strings.Contains(appContentSecurityPolicy, "media-src https:") {
		t.Fatalf("CSP unexpectedly allows external media: %s", appContentSecurityPolicy)
	}
}
