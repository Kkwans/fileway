package fbhttp

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/gorilla/mux"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/tags"
)

type tagCreateRequest struct {
	Name  string `json:"name"`
	Color string `json:"color"`
}

type tagUpdateRequest struct {
	Name  *string `json:"name,omitempty"`
	Color *string `json:"color,omitempty"`
}

type tagPathRequest struct {
	Path     string `json:"path"`
	WirePath string `json:"wirePath,omitempty"`
}

func tagResourcePath(request tagPathRequest) (string, error) {
	if request.WirePath == "" {
		if request.Path == "" || strings.ContainsRune(request.Path, '\x00') {
			return "", fberrors.ErrInvalidRequestParams
		}
		return pathmeta.Clean(request.Path), nil
	}
	path, err := decodeResourceWirePath(request.WirePath)
	if err != nil {
		return "", err
	}
	if request.Path != "" && pathmeta.Clean(request.Path) != files.DisplayPath(path) {
		return "", fmt.Errorf("标签显示路径与原始路径不一致")
	}
	return path, nil
}

var tagsGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.user.Perm.Admin {
		if err := d.store.Tags.ClaimLegacy(d.user.ID); err != nil {
			return http.StatusInternalServerError, err
		}
	}
	t, err := d.store.Tags.GetAll(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, t)
})

var tagsPostHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req tagCreateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	if req.Name == "" {
		return http.StatusBadRequest, fberrors.ErrInvalidRequestParams
	}

	if req.Color == "" {
		req.Color = "#2196F3" // default blue
	}

	tag, err := d.store.Tags.Create(d.user.ID, req.Name, req.Color)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, tag)
})

var tagPutHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req tagUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	tag, err := d.store.Tags.UpdateFields(d.user.ID, id, req.Name, req.Color)
	if err != nil {
		if errors.Is(err, tags.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, tag)
})

var tagDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	err := d.store.Tags.Delete(d.user.ID, id)
	if err != nil {
		if errors.Is(err, tags.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
})

var tagAddPathHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req tagPathRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	path, err := tagResourcePath(req)
	if err != nil {
		return http.StatusBadRequest, err
	}
	if !d.Check(path) {
		return http.StatusForbidden, fmt.Errorf("没有访问标签路径的权限")
	}
	if _, err := d.user.Fs.Stat(path); err != nil {
		return errToStatus(err), err
	}
	tag, err := d.store.Tags.AddPath(d.user.ID, id, path)
	if err != nil {
		if errors.Is(err, tags.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, tag)
})

var tagRemovePathHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req tagPathRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	path, err := tagResourcePath(req)
	if err != nil {
		return http.StatusBadRequest, err
	}
	// Unlinking owned metadata must still work after the file was moved,
	// deleted, or its access rule changed. It never reads or deletes the file.
	tag, err := d.store.Tags.RemovePath(d.user.ID, id, path)
	if err != nil {
		if errors.Is(err, tags.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, tag)
})
