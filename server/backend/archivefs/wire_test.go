package archivefs

import (
	"context"
	"encoding/json"
	"runtime"
	"testing"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/mholt/archives"
	"github.com/spf13/afero"
)

func TestArchiveEntryWireDecodesOnceAndRejectsZipSlip(t *testing.T) {
	for wire, expected := range map[string]string{".": ".", "a%252Fb%20%2B%3F%23": "a%2Fb +?#", "folder/%E4%B8%AD%E6%96%87": "folder/中文"} {
		actual, err := DecodeEntryWirePath(wire)
		if err != nil || actual != expected {
			t.Fatalf("decode %q=%q %v", wire, actual, err)
		}
	}
	for _, wire := range []string{"/absolute", "%2Fabsolute", "../escape", "%2E%2E/escape", "a%2Fb", "bad%00", "bad%", "C%3A/outside", "..%5Cescape"} {
		if path, err := DecodeEntryWirePath(wire); err == nil {
			t.Fatalf("unsafe wire %q accepted as %q", wire, path)
		}
	}
}

func TestArchiveFilesystemNamesKeepSpacesAndLinuxBackslashes(t *testing.T) {
	filesystem := testFilesystem(t)
	source, output := "/ bundle.zip", "/ output "
	if runtime.GOOS == "windows" {
		output = "/output"
	}
	if runtime.GOOS != "windows" {
		source = "/ bundle\\name.zip "
	}
	writeArchive(t, filesystem, source, archives.Zip{}, map[string]string{"file.txt": "owned"})
	if err := filesystem.MkdirAll(output, 0o750); err != nil {
		t.Fatal(err)
	}
	listing, err := List(context.Background(), filesystem, source, Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if listing.ArchivePath != source {
		t.Fatalf("source identity normalized away: %q", listing.ArchivePath)
	}
	if _, err := Extract(context.Background(), filesystem, ExtractOptions{ArchivePath: source, Destination: output, Selected: []string{"file.txt"}}, nil); err != nil {
		t.Fatal(err)
	}
	if content, err := afero.ReadFile(filesystem, output+"/file.txt"); err != nil || string(content) != "owned" {
		t.Fatalf("wrong output identity: %q %v", content, err)
	}
	if runtime.GOOS == "windows" {
		for _, path := range []string{"/\xff.zip", "/folder\\bundle.zip"} {
			if _, err := NormalizeFilesystemPath(path); err == nil {
				t.Fatalf("Windows accepted nonrepresentable path %q", path)
			}
		}
	} else {
		if raw, err := DecodeEntryWirePath("folder%5Cfile.txt"); err != nil || raw != "folder\\file.txt" {
			t.Fatalf("legal Linux entry backslash was changed: %q %v", raw, err)
		}
	}
}

func TestArchiveReportWireRoundTripAndLegacyUnverifiedResult(t *testing.T) {
	report := ExtractReport{ArchivePath: "/中文.zip", Destination: "/ output ", Selected: []string{"a%2Fb +?#"}, Skipped: []SkippedEntry{{Path: "a%2Fb +?#", Reason: "exists"}}}
	if runtime.GOOS != "windows" {
		report.ArchivePath = "/\xff.zip"
		report.Selected = []string{"\xfe +%?#"}
	}
	data, err := json.Marshal(report)
	if err != nil {
		t.Fatal(err)
	}
	var decoded ExtractReport
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.ArchivePath != report.ArchivePath || decoded.Destination != report.Destination || decoded.Selected[0] != report.Selected[0] || decoded.PathsUnverified {
		t.Fatalf("report identity lost: %#v", decoded)
	}
	var wire map[string]interface{}
	if err := json.Unmarshal(data, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["archiveWirePath"] != files.EncodeWirePath(report.ArchivePath) || wire["archivePath"] != files.DisplayPath(report.ArchivePath) {
		t.Fatalf("display/raw not separated: %s", data)
	}
	if err := json.Unmarshal([]byte(`{"archivePath":"/old�.zip","destination":"/output","selected":["file.txt"]}`), &decoded); err != nil {
		t.Fatal(err)
	}
	if !decoded.PathsUnverified {
		t.Fatal("legacy damaged result was certified")
	}
	data, err = json.Marshal(decoded)
	if err != nil {
		t.Fatal(err)
	}
	wire = make(map[string]interface{})
	if err := json.Unmarshal(data, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["pathsVerified"] != false {
		t.Fatalf("legacy status absent: %s", data)
	}
	if _, invented := wire["archiveWirePath"]; invented {
		t.Fatalf("legacy report invented wire identity: %s", data)
	}
}
