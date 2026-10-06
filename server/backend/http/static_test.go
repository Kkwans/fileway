package fbhttp

import "testing"

func TestAcceptsContentEncoding(t *testing.T) {
	tests := []struct {
		header    string
		candidate string
		accepted  bool
	}{
		{header: "gzip, br", candidate: "br", accepted: true},
		{header: "gzip, br;q=0", candidate: "br", accepted: false},
		{header: "gzip;q=0.5", candidate: "gzip", accepted: true},
		{header: "xgzip", candidate: "gzip", accepted: false},
		{header: "*;q=1", candidate: "br", accepted: true},
		{header: "*;q=0", candidate: "gzip", accepted: false},
	}
	for _, test := range tests {
		if got := acceptsContentEncoding(test.header, test.candidate); got != test.accepted {
			t.Errorf("acceptsContentEncoding(%q, %q) = %v, want %v", test.header, test.candidate, got, test.accepted)
		}
	}
}

func TestHashedStaticAssetPolicy(t *testing.T) {
	if !hashedStaticAsset.MatchString("/assets/index-BKW_yQBC.js") {
		t.Fatal("Vite hash should be recognized as immutable")
	}
	if hashedStaticAsset.MatchString("/vditor/dist/index.js") {
		t.Fatal("unhashed editor asset must not be marked immutable")
	}
}
