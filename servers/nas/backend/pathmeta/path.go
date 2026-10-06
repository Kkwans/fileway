package pathmeta

import (
	"path"
	"strings"
)

// Clean normalizes an application path without changing Linux case semantics.
// Backslashes remain untouched because they are valid Linux filename bytes.
func Clean(value string) string {
	return path.Clean("/" + strings.TrimPrefix(value, "/"))
}

// Contains reports whether candidate is prefix itself or one of its
// descendants. Similar sibling prefixes such as /docs-old for /docs do not
// match.
func Contains(candidate, prefix string) bool {
	candidate = Clean(candidate)
	prefix = Clean(prefix)
	return prefix == "/" || candidate == prefix || strings.HasPrefix(candidate, prefix+"/")
}

// Rewrite replaces an exact path or directory prefix and returns the
// normalized original unchanged when it does not belong to that subtree.
func Rewrite(candidate, from, to string) (string, bool) {
	candidate = Clean(candidate)
	from = Clean(from)
	to = Clean(to)
	if !Contains(candidate, from) {
		return candidate, false
	}

	if candidate == from {
		return to, true
	}
	if from == "/" {
		return path.Join(to, strings.TrimPrefix(candidate, "/")), true
	}
	return path.Clean(to + strings.TrimPrefix(candidate, from)), true
}
