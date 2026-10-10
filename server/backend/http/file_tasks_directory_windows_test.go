//go:build windows

package fbhttp

import (
	"github.com/Kkwans/nas-file-browser/backend/files"
	"golang.org/x/sys/windows"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
)

func TestDirectoryPublicationWindowsActualPrivateAliasIsForbiddenOverHTTP(t *testing.T) {
	f := newDirectoryRestartFixture(t, "copy")
	f.crash(t, "publishIntent")
	j := f.journal(t)
	_, _, work, _ := directoryPaths(j)
	input, err := windows.UTF16PtrFromString(f.d.user.FullPath(work))
	if err != nil {
		t.Fatal(err)
	}
	buffer := make([]uint16, 32768)
	written, err := windows.GetShortPathName(input, &buffer[0], uint32(len(buffer)))
	if err != nil || written == 0 {
		t.Skipf("Owned filesystem provides no queryable short alias: %v", err)
	}
	short := filepath.Base(windows.UTF16ToString(buffer))
	if strings.EqualFold(short, filepath.Base(work)) || files.IsUploadPartPath(short) {
		t.Skip("No distinct 8.3 alias generated; no system settings modified")
	}
	alias := "/" + short + "/old/nested/old.txt"
	for _, handler := range []handleFunc{resourceGetHandler, rawHandler} {
		response := f.h.request(t, f.userID, handler, http.MethodGet, alias, nil, nil)
		if response.Code != 403 {
			t.Fatalf("actual private alias read status=%d body=%s", response.Code, response.Body.String())
		}
	}
	response := f.h.request(t, f.userID, resourcePostHandler(noopTrashFileCache{}), http.MethodPost, "/"+short+"/unknown.txt", strings.NewReader("forbidden"), nil)
	if response.Code != 403 {
		t.Fatalf("private alias write status=%d", response.Code)
	}
	assertWireOwned(t, f.d.user.Fs, map[string]string{work + "/old/nested/old.txt": "old-tree", work + "/new/nested/new.txt": "new-tree", "/source/nested/new.txt": "new-tree"})
}
