package fbhttp

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/gorilla/mux"

	fberrors "github.com/Kkwans/nas-file-browser/backend/errors"
	"github.com/Kkwans/nas-file-browser/backend/favorites"
)

type favoriteRequest struct {
	Path    string `json:"path"`
	Name    string `json:"name"`
	GroupID string `json:"groupId,omitempty"`
}

type favoriteUpdateRequest struct {
	Name    *string `json:"name,omitempty"`
	Order   *int    `json:"order,omitempty"`
	GroupID *string `json:"groupId,omitempty"`
}

type favoriteGroupRequest struct {
	Name  string `json:"name"`
	Color string `json:"color,omitempty"`
}

type favoriteGroupUpdateRequest struct {
	Name  *string `json:"name,omitempty"`
	Color *string `json:"color,omitempty"`
}

type reorderRequest struct {
	IDs []string `json:"ids"`
}

var favoritesGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if d.user.Perm.Admin {
		if err := d.store.Favorites.ClaimLegacy(d.user.ID); err != nil {
			return http.StatusInternalServerError, err
		}
	}
	favs, err := d.store.Favorites.GetAll(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, favs)
})

var favoritesPostHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req favoriteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	if req.Path == "" {
		return http.StatusBadRequest, fberrors.ErrInvalidRequestParams
	}

	// Get current count for ordering
	all, err := d.store.Favorites.GetAll(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	fav, err := d.store.Favorites.AddToGroup(d.user.ID, req.Path, req.Name, req.GroupID, len(all))
	if err != nil {
		if errors.Is(err, favorites.ErrExist) {
			return http.StatusConflict, fberrors.ErrExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, fav)
})

var favoritePutHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req favoriteUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	fav, err := d.store.Favorites.UpdateFieldsEx(d.user.ID, id, req.Name, req.Order, req.GroupID)
	if err != nil {
		if errors.Is(err, favorites.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, fav)
})

var favoriteDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	err := d.store.Favorites.Delete(d.user.ID, id)
	if err != nil {
		if errors.Is(err, favorites.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
})

var favoritesReorderHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req reorderRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	if err := d.store.Favorites.Reorder(d.user.ID, req.IDs); err != nil {
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
})

// --- Group handlers ---

var favoriteGroupsGetHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	groups, err := d.store.Favorites.GetAllGroups(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}
	return renderJSON(w, r, groups)
})

var favoriteGroupsPostHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req favoriteGroupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	if req.Name == "" {
		return http.StatusBadRequest, fberrors.ErrInvalidRequestParams
	}

	all, err := d.store.Favorites.GetAllGroups(d.user.ID)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	group, err := d.store.Favorites.AddGroup(d.user.ID, req.Name, req.Color, len(all))
	if err != nil {
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, group)
})

var favoriteGroupPutHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req favoriteGroupUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	group, err := d.store.Favorites.UpdateGroupFields(d.user.ID, id, req.Name, req.Color)
	if err != nil {
		if errors.Is(err, favorites.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, group)
})

var favoriteGroupDeleteHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	vars := mux.Vars(r)
	id := vars["id"]

	err := d.store.Favorites.DeleteGroup(d.user.ID, id)
	if err != nil {
		if errors.Is(err, favorites.ErrNotExist) {
			return http.StatusNotFound, fberrors.ErrNotExist
		}
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
})

var favoriteGroupsReorderHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if r.Body == nil {
		return http.StatusBadRequest, fberrors.ErrEmptyRequest
	}

	var req reorderRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return http.StatusBadRequest, err
	}

	if err := d.store.Favorites.ReorderGroups(d.user.ID, req.IDs); err != nil {
		return http.StatusInternalServerError, err
	}

	return http.StatusOK, nil
})
