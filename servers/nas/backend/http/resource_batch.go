package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const maxBatchResourcePaths = 500

type batchResourceRequest struct {
	Paths []string `json:"paths"`
}

type batchResourceResult struct {
	Path   string          `json:"path"`
	Status int             `json:"status"`
	Item   *files.FileInfo `json:"item,omitempty"`
	Error  string          `json:"error,omitempty"`
}

type batchResourceResolver func(normalizedPath string) (*files.FileInfo, error)

func validateBatchResourcePaths(paths []string) error {
	if len(paths) == 0 || len(paths) > maxBatchResourcePaths {
		return fmt.Errorf("批量资源路径数量必须在 1 到 %d 之间", maxBatchResourcePaths)
	}
	return nil
}

func resolveBatchResources(paths []string, resolve batchResourceResolver) []batchResourceResult {
	results := make([]batchResourceResult, 0, len(paths))
	for _, requestedPath := range paths {
		normalizedPath := pathmeta.Clean(requestedPath)
		item, err := resolve(normalizedPath)
		if err != nil {
			results = append(results, batchResourceResult{
				Path: normalizedPath, Status: errToStatus(err), Error: err.Error(),
			})
			continue
		}
		if !item.IsDir {
			item.DetectTypeFromExt()
		}
		results = append(results, batchResourceResult{
			Path: normalizedPath, Status: http.StatusOK, Item: item,
		})
	}
	return results
}

// resourceBatchHandler resolves lightweight metadata in input order. It never
// expands directories, reads file content, or triggers thumbnail generation.
var resourceBatchHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	var request batchResourceRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		return http.StatusBadRequest, fmt.Errorf("批量资源请求格式无效: %w", err)
	}
	if err := validateBatchResourcePaths(request.Paths); err != nil {
		return http.StatusBadRequest, err
	}

	results := resolveBatchResources(request.Paths, func(normalizedPath string) (*files.FileInfo, error) {
		return files.NewFileInfo(&files.FileOptions{
			Fs:         d.user.Fs,
			Path:       normalizedPath,
			Modify:     d.user.Perm.Modify,
			Expand:     false,
			ReadHeader: false,
			Checker:    d,
			Content:    false,
		})
	})

	return renderJSON(w, r, results)
})
