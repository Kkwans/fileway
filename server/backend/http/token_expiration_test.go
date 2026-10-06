package fbhttp

import (
	"testing"
	"time"
)

func TestParseTokenExpirationTime(t *testing.T) {
	tests := []struct {
		name    string
		value   string
		want    time.Duration
		wantErr bool
	}{
		{name: "minimum", value: "10m", want: 10 * time.Minute},
		{name: "common", value: "2h", want: 2 * time.Hour},
		{name: "previous maximum remains valid", value: "24h", want: 24 * time.Hour},
		{name: "maximum", value: "720h", want: 30 * 24 * time.Hour},
		{name: "too short", value: "9m", wantErr: true},
		{name: "too long", value: "720h1m", wantErr: true},
		{name: "invalid", value: "two hours", wantErr: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := parseTokenExpirationTime(tt.value)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("parseTokenExpirationTime(%q) expected error", tt.value)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseTokenExpirationTime(%q) returned error: %v", tt.value, err)
			}
			if got != tt.want {
				t.Fatalf("parseTokenExpirationTime(%q) = %v, want %v", tt.value, got, tt.want)
			}
		})
	}
}
