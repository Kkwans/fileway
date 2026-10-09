package transport

import (
	"net/http"
	"net/url"
	"strings"
	"time"
)

// RequestTimeout keeps ordinary API calls short. A user-requested file digest
// may read a complete movie on the server; it remains cancellable and bounded.
func RequestTimeout(method, endpoint string) time.Duration {
	if method == http.MethodGet {
		parsed, err := url.Parse(endpoint)
		if err == nil && !parsed.IsAbs() && parsed.Host == "" && strings.HasPrefix(parsed.Path, "/api/resources/") {
			switch parsed.Query().Get("checksum") {
			case "md5", "sha1", "sha256", "sha512":
				return 15 * time.Minute
			}
		}
	}
	return 30 * time.Second
}

func (s *Session) requestClient(method, endpoint string) *http.Client {
	if RequestTimeout(method, endpoint) <= 30*time.Second {
		return s.client
	}
	// Clone the selected transport, retaining its direct/tsnet dialer and TLS
	// policy. Disable pooling for this isolated long request to avoid orphaned
	// idle connections. Session cancellation still owns the request context.
	transport, ok := s.client.Transport.(*http.Transport)
	if !ok {
		return s.client
	}
	copyClient := *s.client
	copyTransport := transport.Clone()
	copyTransport.ResponseHeaderTimeout = RequestTimeout(method, endpoint)
	copyTransport.DisableKeepAlives = true
	copyClient.Transport = copyTransport
	return &copyClient
}
