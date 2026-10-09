package archivefs

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
)

func decodeWireSegments(wire string, absolute bool) (string, error) {
	if wire == "" || strings.HasPrefix(wire, "/") != absolute || strings.HasPrefix(wire, "//") || strings.ContainsAny(wire, "?#") {
		return "", ErrUnsafeEntry
	}
	for _, value := range []byte(wire) {
		if value < 0x21 || value > 0x7e {
			return "", ErrUnsafeEntry
		}
	}
	parts := strings.Split(wire, "/")
	for index, part := range parts {
		decoded, err := url.PathUnescape(part)
		if err != nil || strings.ContainsAny(decoded, "/\x00") {
			return "", ErrUnsafeEntry
		}
		parts[index] = decoded
	}
	return strings.Join(parts, "/"), nil
}

// DecodeEntryWirePath accepts only a relative archive path. It decodes bytes
// once and applies the same ZipSlip checks used for real archive entries.
func DecodeEntryWirePath(wire string) (string, error) {
	raw, err := decodeWireSegments(wire, false)
	if err != nil {
		return "", err
	}
	return cleanEntryPath(raw)
}

func filesystemJSONPath(display, wire string) (string, bool, error) {
	if wire == "" {
		return display, strings.ContainsRune(display, '\uFFFD'), nil
	}
	raw, err := decodeWireSegments(wire, true)
	if err != nil {
		return "", false, err
	}
	raw, err = NormalizeFilesystemPath(raw)
	if err != nil {
		return "", false, err
	}
	if display != "" && display != files.DisplayPath(raw) {
		return "", false, fmt.Errorf("显示路径与原始路径不一致")
	}
	return raw, false, nil
}

func entryJSONPath(display, wire string) (string, bool, error) {
	if wire == "" {
		return display, strings.ContainsRune(display, '\uFFFD'), nil
	}
	raw, err := DecodeEntryWirePath(wire)
	if err != nil {
		return "", false, err
	}
	if display != "" && display != files.DisplayPath(raw) {
		return "", false, fmt.Errorf("条目显示路径与原始路径不一致")
	}
	return raw, false, nil
}

func (entry Entry) MarshalJSON() ([]byte, error) {
	type alias Entry
	wire := ""
	if !entry.PathUnverified {
		wire = files.EncodeWirePath(entry.Path)
	}
	return json.Marshal(struct {
		alias
		Path         string `json:"path"`
		Name         string `json:"name"`
		WirePath     string `json:"wirePath,omitempty"`
		PathVerified bool   `json:"pathVerified"`
	}{
		alias: alias(entry), Path: files.DisplayPath(entry.Path), Name: files.DisplayName(entry.Name), WirePath: wire, PathVerified: !entry.PathUnverified,
	})
}

func (entry *Entry) UnmarshalJSON(data []byte) error {
	type alias Entry
	var row struct {
		alias
		WirePath     string `json:"wirePath"`
		PathVerified *bool  `json:"pathVerified"`
	}
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	raw, unknown, err := entryJSONPath(row.Path, row.WirePath)
	if err != nil {
		return err
	}
	*entry = Entry(row.alias)
	entry.Path = raw
	entry.PathUnverified = unknown || row.PathVerified != nil && !*row.PathVerified
	return nil
}

func (listing Listing) MarshalJSON() ([]byte, error) {
	type alias Listing
	wire := ""
	if !listing.PathUnverified {
		wire = files.EncodeWirePath(listing.ArchivePath)
	}
	return json.Marshal(struct {
		alias
		ArchivePath     string `json:"archivePath"`
		ArchiveWirePath string `json:"archiveWirePath,omitempty"`
		PathVerified    bool   `json:"pathVerified"`
	}{
		alias: alias(listing), ArchivePath: files.DisplayPath(listing.ArchivePath), ArchiveWirePath: wire, PathVerified: !listing.PathUnverified,
	})
}

func (listing *Listing) UnmarshalJSON(data []byte) error {
	type alias Listing
	var row struct {
		alias
		ArchiveWirePath string `json:"archiveWirePath"`
		PathVerified    *bool  `json:"pathVerified"`
	}
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	raw, unknown, err := filesystemJSONPath(row.ArchivePath, row.ArchiveWirePath)
	if err != nil {
		return err
	}
	*listing = Listing(row.alias)
	listing.ArchivePath = raw
	listing.PathUnverified = unknown || row.PathVerified != nil && !*row.PathVerified
	return nil
}

func (entry BlockedEntry) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Path     string `json:"path"`
		WirePath string `json:"wirePath"`
		Reason   string `json:"reason"`
	}{
		files.DisplayPath(entry.Path), files.EncodeWirePath(entry.Path), entry.Reason,
	})
}

func (entry SkippedEntry) MarshalJSON() ([]byte, error) {
	wire := ""
	if !entry.PathUnverified {
		wire = files.EncodeWirePath(entry.Path)
	}
	return json.Marshal(struct {
		Path     string `json:"path"`
		WirePath string `json:"wirePath,omitempty"`
		Reason   string `json:"reason"`
	}{
		files.DisplayPath(entry.Path), wire, entry.Reason,
	})
}

func (entry *SkippedEntry) UnmarshalJSON(data []byte) error {
	var row struct {
		Path     string `json:"path"`
		WirePath string `json:"wirePath"`
		Reason   string `json:"reason"`
	}
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	raw, unknown, err := entryJSONPath(row.Path, row.WirePath)
	if err != nil {
		return err
	}
	*entry = SkippedEntry{Path: raw, Reason: row.Reason, PathUnverified: unknown}
	return nil
}

func (report ExtractReport) MarshalJSON() ([]byte, error) {
	type alias ExtractReport
	selected, wires := make([]string, len(report.Selected)), make([]string, len(report.Selected))
	for index, raw := range report.Selected {
		selected[index] = files.DisplayPath(raw)
		wires[index] = files.EncodeWirePath(raw)
	}
	archiveWire, destinationWire := "", ""
	if !report.PathsUnverified {
		archiveWire = files.EncodeWirePath(report.ArchivePath)
		destinationWire = files.EncodeWirePath(report.Destination)
	} else {
		wires = nil
	}
	return json.Marshal(struct {
		alias
		ArchivePath         string   `json:"archivePath"`
		Destination         string   `json:"destination"`
		Selected            []string `json:"selected"`
		ArchiveWirePath     string   `json:"archiveWirePath,omitempty"`
		DestinationWirePath string   `json:"destinationWirePath,omitempty"`
		SelectedWirePaths   []string `json:"selectedWirePaths,omitempty"`
		PathsVerified       bool     `json:"pathsVerified"`
	}{
		alias: alias(report), ArchivePath: files.DisplayPath(report.ArchivePath), Destination: files.DisplayPath(report.Destination), Selected: selected,
		ArchiveWirePath: archiveWire, DestinationWirePath: destinationWire, SelectedWirePaths: wires, PathsVerified: !report.PathsUnverified,
	})
}

func (report *ExtractReport) UnmarshalJSON(data []byte) error {
	type alias ExtractReport
	var row struct {
		alias
		ArchiveWirePath     string   `json:"archiveWirePath"`
		DestinationWirePath string   `json:"destinationWirePath"`
		SelectedWirePaths   []string `json:"selectedWirePaths"`
		PathsVerified       *bool    `json:"pathsVerified"`
	}
	if err := json.Unmarshal(data, &row); err != nil {
		return err
	}
	archive, unknownArchive, err := filesystemJSONPath(row.ArchivePath, row.ArchiveWirePath)
	if err != nil {
		return err
	}
	destination, unknownDestination, err := filesystemJSONPath(row.Destination, row.DestinationWirePath)
	if err != nil {
		return err
	}
	selected := row.Selected
	unknown := unknownArchive || unknownDestination
	if row.SelectedWirePaths != nil && len(row.SelectedWirePaths) != len(selected) {
		return fmt.Errorf("选择条目与原始路径数量不一致")
	}
	for index, display := range selected {
		wire := ""
		if row.SelectedWirePaths != nil {
			wire = row.SelectedWirePaths[index]
		}
		raw, lost, err := entryJSONPath(display, wire)
		if err != nil {
			return err
		}
		selected[index] = raw
		unknown = unknown || lost
	}
	*report = ExtractReport(row.alias)
	report.ArchivePath = archive
	report.Destination = destination
	report.Selected = selected
	report.PathsUnverified = unknown || row.PathsVerified != nil && !*row.PathsVerified
	return nil
}
