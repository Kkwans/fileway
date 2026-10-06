package files

import (
	"sort"
	"strings"

	"github.com/maruel/natural"
)

// Listing is a collection of files.
type Listing struct {
	Items    []*FileInfo `json:"items"`
	NumDirs  int         `json:"numDirs"`
	NumFiles int         `json:"numFiles"`
	Sorting  Sorting     `json:"sorting"`
}

// ApplySort applies the sort order using .Order and .Sort
func (l Listing) ApplySort() {
	switch l.Sorting.By {
	case "name":
		// byName already honors Sorting.Asc for name comparison.
		// Directories stay before files in both directions.
		sort.Sort(byName(l))
	case "size":
		if !l.Sorting.Asc {
			sort.Sort(sort.Reverse(bySize(l)))
		} else {
			sort.Sort(bySize(l))
		}
	case "modified":
		if !l.Sorting.Asc {
			sort.Sort(sort.Reverse(byModified(l)))
		} else {
			sort.Sort(byModified(l))
		}
	default:
		sort.Sort(byName(l))
	}
}

// Implement sorting for Listing
type byName Listing
type bySize Listing
type byModified Listing

// By Name
func (l byName) Len() int {
	return len(l.Items)
}

func (l byName) Swap(i, j int) {
	l.Items[i], l.Items[j] = l.Items[j], l.Items[i]
}

// Directories first; names follow Asc. Comparison must use (i, j) not (j, i).
func (l byName) Less(i, j int) bool {
	if l.Items[i].IsDir && !l.Items[j].IsDir {
		return true
	}
	if !l.Items[i].IsDir && l.Items[j].IsDir {
		return false
	}

	ni := strings.ToLower(l.Items[i].Name)
	nj := strings.ToLower(l.Items[j].Name)
	if l.Sorting.Asc {
		return natural.Less(ni, nj)
	}
	return natural.Less(nj, ni)
}

// By Size
func (l bySize) Len() int {
	return len(l.Items)
}

func (l bySize) Swap(i, j int) {
	l.Items[i], l.Items[j] = l.Items[j], l.Items[i]
}

const directoryOffset = -1 << 31 // = math.MinInt32
func (l bySize) Less(i, j int) bool {
	iSize, jSize := l.Items[i].Size, l.Items[j].Size
	if l.Items[i].IsDir {
		iSize = directoryOffset + iSize
	}
	if l.Items[j].IsDir {
		jSize = directoryOffset + jSize
	}
	return iSize < jSize
}

// By Modified
func (l byModified) Len() int {
	return len(l.Items)
}

func (l byModified) Swap(i, j int) {
	l.Items[i], l.Items[j] = l.Items[j], l.Items[i]
}

func (l byModified) Less(i, j int) bool {
	iModified, jModified := l.Items[i].ModTime, l.Items[j].ModTime
	return iModified.Sub(jModified) < 0
}
