package fbhttp

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
)

const maxBatchResourcePaths = 500

type batchResourceRequest struct {
	Paths     []string `json:"paths"`
	WirePaths []string `json:"wirePaths,omitempty"`
}

type batchResourceResult struct {
	Path   string          `json:"path"`
	Status int             `json:"status"`
	Item   *files.FileInfo `json:"item,omitempty"`
	Error  string          `json:"error,omitempty"`
}

func (result batchResourceResult) MarshalJSON() ([]byte, error) {
	type resultAlias batchResourceResult
	return json.Marshal(struct {
		resultAlias
		Path     string `json:"path"`
		WirePath string `json:"wirePath"`
	}{resultAlias(result), files.DisplayPath(result.Path), files.EncodeWirePath(result.Path)})
}

func batchResourcePaths(request batchResourceRequest) ([]string, error) {
	if request.WirePaths == nil {
		if err := validateBatchResourcePaths(request.Paths); err != nil {
			return nil, err
		}
		for _, path := range request.Paths {
			if path == "" || strings.ContainsRune(path, '\x00') {
				return nil, fmt.Errorf("批量资源路径无效")
			}
		}
		return request.Paths, nil
	}
	if err := validateBatchResourcePaths(request.WirePaths); err != nil {
		return nil, err
	}
	if request.Paths != nil && len(request.Paths) != len(request.WirePaths) {
		return nil, fmt.Errorf("批量显示路径与原始路径数量不一致")
	}
	paths := make([]string, len(request.WirePaths))
	for index, wire := range request.WirePaths {
		path, err := decodeResourceWirePath(wire)
		if err != nil {
			return nil, err
		}
		if request.Paths != nil && pathmeta.Clean(request.Paths[index]) != files.DisplayPath(path) {
			return nil, fmt.Errorf("批量显示路径与原始路径不一致")
		}
		paths[index] = path
	}
	return paths, nil
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
	paths, err := batchResourcePaths(request)
	if err != nil {
		return http.StatusBadRequest, err
	}

	results := resolveBatchResources(paths, func(normalizedPath string) (*files.FileInfo, error) {
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
