package analysis

// RecommendDuplicateKeeper compares full timestamp precision on the server.
// Browsers truncate RFC3339 timestamps to milliseconds; they must not decide
// whether two distinct nanosecond birth times tie.
func RecommendDuplicateKeeper(files []DuplicateFile) (string, string) {
	if len(files) < 2 {
		return "", "truncated"
	}
	oldest := 0
	tied := false
	for index, file := range files {
		if file.Created == nil {
			return "", "missing-created"
		}
		if file.Identity == nil || file.Identity.Links != 1 || !file.Identity.IsRegular() {
			return "", "unsafe-identity"
		}
		if index == 0 {
			continue
		}
		if file.Created.Before(*files[oldest].Created) {
			oldest = index
			tied = false
		} else if file.Created.Equal(*files[oldest].Created) {
			tied = true
		}
	}
	if tied {
		return "", "tied-created"
	}
	return files[oldest].Path, "oldest-created"
}
