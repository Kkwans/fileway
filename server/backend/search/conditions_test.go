package search

import "testing"

func TestCompositeTypeConditions(t *testing.T) {
	tests := []struct {
		query   string
		matches []string
		rejects []string
	}{
		{
			query:   "type:markdown",
			matches: []string{"README.md", "guide.markdown"},
			rejects: []string{"config.yaml", "main.go"},
		},
		{
			query:   "type:config",
			matches: []string{"config.json", "app.yaml", "settings.toml", ".env"},
			rejects: []string{"README.md", "main.java"},
		},
		{
			query:   "type:code",
			matches: []string{"Main.java", "task.py", "server.go", "app.js", "view.tsx"},
			rejects: []string{"README.md", "config.yaml"},
		},
	}

	for _, test := range tests {
		t.Run(test.query, func(t *testing.T) {
			options := parseSearch(test.query)
			if len(options.Conditions) != 1 {
				t.Fatalf("expected one condition, got %d", len(options.Conditions))
			}
			for _, path := range test.matches {
				if !options.Conditions[0](path) {
					t.Errorf("expected %q to match %s", path, test.query)
				}
			}
			for _, path := range test.rejects {
				if options.Conditions[0](path) {
					t.Errorf("expected %q not to match %s", path, test.query)
				}
			}
		})
	}
}
