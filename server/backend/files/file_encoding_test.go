package files

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestFileInfoJSONPreservesNonUTF8FilenameAddressability(t *testing.T) {
	// GBK bytes for "中文.txt". Linux accepts these bytes as a filename even
	// though they are not a valid UTF-8 string.
	rawName := string([]byte{0xd6, 0xd0, 0xce, 0xc4}) + ".txt"
	rawPath := "/tmp/encoding/" + rawName
	file := &FileInfo{Path: rawPath, Name: rawName, Extension: ".txt"}

	encoded, err := json.Marshal(file)
	if err != nil {
		t.Fatalf("marshal file info: %v", err)
	}
	jsonText := string(encoded)

	if !strings.Contains(jsonText, `"name":"中文.txt"`) {
		t.Fatalf("display name missing from JSON: %s", jsonText)
	}
	if !strings.Contains(jsonText, `"path":"/tmp/encoding/中文.txt"`) {
		t.Fatalf("display path missing from JSON: %s", jsonText)
	}
	if !strings.Contains(jsonText, `"wirePath":"/tmp/encoding/%D6%D0%CE%C4.txt"`) {
		t.Fatalf("wire path missing from JSON: %s", jsonText)
	}
	if file.Name != rawName || file.Path != rawPath {
		t.Fatalf("marshal mutated filesystem values: name=%q path=%q", file.Name, file.Path)
	}
}

func TestEncodeWirePathEscapesBytesAndPreservesSeparators(t *testing.T) {
	got := encodeWirePath("/a b/中文.txt")
	want := "/a%20b/%E4%B8%AD%E6%96%87.txt"
	if got != want {
		t.Fatalf("encodeWirePath() = %q, want %q", got, want)
	}
}
