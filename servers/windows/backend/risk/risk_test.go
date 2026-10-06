package risk

import "testing"

func TestClassifyUsesDirectoryBoundariesAndLinuxCase(t *testing.T) {
	t.Parallel()

	tests := []struct {
		path string
		want Level
	}{
		{path: "/etc", want: High},
		{path: "/etc/a", want: High},
		{path: "/etc2", want: Low},
		{path: "/Etc", want: Low},
		{path: "/lib64", want: High},
		{path: "/lib64/modules", want: High},
		{path: "/library", want: Low},
		{path: "/volume12/@docker", want: Medium},
		{path: "/volume12/@docker/containers", want: Medium},
		{path: "/volume12/@docker2", want: Low},
		{path: "/volume12/@thumbnail", want: Medium},
		{path: "/volume12/@thumbnail/cache", want: Medium},
		{path: "/volume12/@thumbnail-old", want: Low},
		{path: "/volumeUSB2/@docker", want: Low},
		{path: "/database", want: Medium},
		{path: "/database-backup", want: Low},
		{path: "/home/Kkwans", want: Low},
		{path: "etc", want: Low},
	}

	for _, test := range tests {
		test := test
		t.Run(test.path, func(t *testing.T) {
			t.Parallel()
			if got := Classify(test.path); got != test.want {
				t.Fatalf("Classify(%q) = %q, want %q", test.path, got, test.want)
			}
		})
	}
}
