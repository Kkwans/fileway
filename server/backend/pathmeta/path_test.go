package pathmeta

import "testing"

func TestRewriteHonorsDirectoryBoundariesAndLinuxCase(t *testing.T) {
	tests := []struct {
		name      string
		candidate string
		from      string
		to        string
		want      string
		matched   bool
	}{
		{name: "exact", candidate: "/docs", from: "/docs", to: "/archive", want: "/archive", matched: true},
		{name: "descendant", candidate: "/docs/2026/report.md", from: "/docs", to: "/archive", want: "/archive/2026/report.md", matched: true},
		{name: "similar prefix", candidate: "/docs-old/report.md", from: "/docs", to: "/archive", want: "/docs-old/report.md", matched: false},
		{name: "case sensitive", candidate: "/Docs/report.md", from: "/docs", to: "/archive", want: "/Docs/report.md", matched: false},
		{name: "root", candidate: "/docs/report.md", from: "/", to: "/mnt", want: "/mnt/docs/report.md", matched: true},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, matched := Rewrite(test.candidate, test.from, test.to)
			if got != test.want || matched != test.matched {
				t.Fatalf("Rewrite() = %q, %v; want %q, %v", got, matched, test.want, test.matched)
			}
		})
	}
}
