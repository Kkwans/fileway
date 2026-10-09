package files

// DisplayPath and DisplayName expose the same safe filesystem presentation
// used by FileInfo, without changing the original path bytes in storage.
func DisplayPath(value string) string { return displayPath(value) }
func DisplayName(value string) string { return displayName(value) }

// EncodeWirePath preserves the original filesystem bytes, escaping each path
// segment exactly once while leaving directory separators intact.
func EncodeWirePath(value string) string { return encodeWirePath(value) }
