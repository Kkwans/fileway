package files

import (
	"testing"
	"time"
)

func TestApplySortNameAscendingPutsCBeforeD(t *testing.T) {
	listing := Listing{
		Sorting: Sorting{By: "name", Asc: true},
		Items: []*FileInfo{
			{Name: "D", Path: "/D", IsDir: true, ModTime: time.Now()},
			{Name: "C", Path: "/C", IsDir: true, ModTime: time.Now()},
			{Name: "b.txt", Path: "/b.txt", IsDir: false},
			{Name: "a.txt", Path: "/a.txt", IsDir: false},
		},
	}
	listing.ApplySort()
	if listing.Items[0].Name != "C" || listing.Items[1].Name != "D" {
		t.Fatalf("dirs order = %s,%s want C,D", listing.Items[0].Name, listing.Items[1].Name)
	}
	if listing.Items[2].Name != "a.txt" || listing.Items[3].Name != "b.txt" {
		t.Fatalf("files order = %s,%s want a.txt,b.txt", listing.Items[2].Name, listing.Items[3].Name)
	}
}

func TestApplySortNameDescendingKeepsDirsFirst(t *testing.T) {
	listing := Listing{
		Sorting: Sorting{By: "name", Asc: false},
		Items: []*FileInfo{
			{Name: "a.txt", Path: "/a.txt", IsDir: false},
			{Name: "C", Path: "/C", IsDir: true},
			{Name: "D", Path: "/D", IsDir: true},
		},
	}
	listing.ApplySort()
	if !listing.Items[0].IsDir || !listing.Items[1].IsDir {
		t.Fatalf("dirs not first: %#v", listing.Items)
	}
	if listing.Items[0].Name != "D" || listing.Items[1].Name != "C" {
		t.Fatalf("desc dirs = %s,%s want D,C", listing.Items[0].Name, listing.Items[1].Name)
	}
}
