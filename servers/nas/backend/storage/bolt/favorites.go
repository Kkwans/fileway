package bolt

import (
	"errors"

	"github.com/asdine/storm/v3"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
)

// Favorite and FavoriteGroup deliberately keep Storm's historical bucket
// names while separating persistence JSON from the public domain structs.
// Storm's default JSON codec otherwise drops UserID because the HTTP models
// mark that field json:"-".
type Favorite struct {
	ID      string `json:"id" storm:"id"`
	UserID  uint   `json:"userId" storm:"index"`
	Path    string `json:"path" storm:"index"`
	Name    string `json:"name"`
	GroupID string `json:"groupId,omitempty"`
	AddedAt int64  `json:"addedAt"`
	Order   int    `json:"order"`
}

type FavoriteGroup struct {
	ID     string `json:"id" storm:"id"`
	UserID uint   `json:"userId" storm:"index"`
	Name   string `json:"name"`
	Order  int    `json:"order"`
	Color  string `json:"color,omitempty"`
}

type favoritesBackend struct {
	db *storm.DB
}

func (f favoritesBackend) ClaimLegacy(userID uint) error {
	var allFavorites []*Favorite
	if err := f.db.All(&allFavorites); err != nil && !errors.Is(err, storm.ErrNotFound) {
		return err
	}
	for _, favorite := range allFavorites {
		if favorite.UserID == 0 {
			favorite.UserID = userID
			if err := f.db.Update(favorite); err != nil {
				return err
			}
		}
	}

	var allGroups []*FavoriteGroup
	if err := f.db.All(&allGroups); err != nil && !errors.Is(err, storm.ErrNotFound) {
		return err
	}
	for _, group := range allGroups {
		if group.UserID == 0 {
			group.UserID = userID
			if err := f.db.Update(group); err != nil {
				return err
			}
		}
	}
	return nil
}

// migrateOwners repairs rows written before the Bolt persistence model was
// split from the public JSON model. Their UserID still exists in Storm's index
// even though it is missing from the encoded row value.
func (f favoritesBackend) migrateOwners(userIDs []uint) error {
	for _, userID := range userIDs {
		var favoriteRecords []*Favorite
		if err := f.db.Find("UserID", userID, &favoriteRecords); err != nil && !errors.Is(err, storm.ErrNotFound) {
			return err
		}
		for _, record := range favoriteRecords {
			if record.UserID == userID {
				continue
			}
			record.UserID = userID
			if err := f.db.Update(record); err != nil {
				return err
			}
		}

		var groupRecords []*FavoriteGroup
		if err := f.db.Find("UserID", userID, &groupRecords); err != nil && !errors.Is(err, storm.ErrNotFound) {
			return err
		}
		for _, record := range groupRecords {
			if record.UserID == userID {
				continue
			}
			record.UserID = userID
			if err := f.db.Update(record); err != nil {
				return err
			}
		}
	}
	return nil
}

func (f favoritesBackend) GetAll(userID uint) ([]*favorites.Favorite, error) {
	var records []*Favorite
	err := f.db.Find("UserID", userID, &records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*favorites.Favorite{}, nil
	}
	if err != nil {
		return nil, err
	}
	return favoriteDomains(records), nil
}

func (f favoritesBackend) GetAllForPathMutation() ([]*favorites.Favorite, error) {
	var records []*Favorite
	err := f.db.All(&records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*favorites.Favorite{}, nil
	}
	if err != nil {
		return nil, err
	}
	return favoriteDomains(records), nil
}

func (f favoritesBackend) GetByID(userID uint, id string) (*favorites.Favorite, error) {
	var record Favorite
	err := f.db.One("ID", id, &record)
	if errors.Is(err, storm.ErrNotFound) {
		return nil, favorites.ErrNotExist
	}
	if err != nil {
		return nil, err
	}
	if record.UserID == userID {
		return record.domain(), nil
	}
	// Records created before per-user ownership are claimed on first mutation.
	if record.UserID == 0 {
		record.UserID = userID
		if err := f.db.Update(&record); err != nil {
			return nil, err
		}
		return record.domain(), nil
	}
	return nil, favorites.ErrNotExist
}

func (f favoritesBackend) GetByPath(userID uint, path string) (*favorites.Favorite, error) {
	all, err := f.GetAll(userID)
	if err != nil {
		return nil, err
	}
	for _, favorite := range all {
		if favorite.Path == path {
			return favorite, nil
		}
	}
	return nil, favorites.ErrNotExist
}

func (f favoritesBackend) Save(favorite *favorites.Favorite) error {
	return f.db.Save(newFavoriteRecord(favorite))
}

func (f favoritesBackend) Update(favorite *favorites.Favorite) error {
	return f.db.Update(newFavoriteRecord(favorite))
}

func (f favoritesBackend) UpdatePath(id string, path string) error {
	return f.db.UpdateField(&Favorite{ID: id}, "Path", path)
}

func (f favoritesBackend) UpdateGroupID(id string, groupID string) error {
	return f.db.UpdateField(&Favorite{ID: id}, "GroupID", groupID)
}

func (f favoritesBackend) Delete(id string) error {
	return f.db.DeleteStruct(&Favorite{ID: id})
}

func (f favoritesBackend) GetAllGroups(userID uint) ([]*favorites.FavoriteGroup, error) {
	var records []*FavoriteGroup
	err := f.db.Find("UserID", userID, &records)
	if errors.Is(err, storm.ErrNotFound) {
		return []*favorites.FavoriteGroup{}, nil
	}
	if err != nil {
		return nil, err
	}
	groups := make([]*favorites.FavoriteGroup, len(records))
	for index, record := range records {
		groups[index] = record.domain()
	}
	return groups, nil
}

func (f favoritesBackend) GetGroupByID(userID uint, id string) (*favorites.FavoriteGroup, error) {
	var record FavoriteGroup
	err := f.db.One("ID", id, &record)
	if errors.Is(err, storm.ErrNotFound) {
		return nil, favorites.ErrNotExist
	}
	if err != nil {
		return nil, err
	}
	if record.UserID == userID {
		return record.domain(), nil
	}
	if record.UserID == 0 {
		record.UserID = userID
		if err := f.db.Update(&record); err != nil {
			return nil, err
		}
		return record.domain(), nil
	}
	return nil, favorites.ErrNotExist
}

func (f favoritesBackend) SaveGroup(group *favorites.FavoriteGroup) error {
	return f.db.Save(newFavoriteGroupRecord(group))
}

func (f favoritesBackend) UpdateGroup(group *favorites.FavoriteGroup) error {
	return f.db.Update(newFavoriteGroupRecord(group))
}

func (f favoritesBackend) DeleteGroup(id string) error {
	return f.db.DeleteStruct(&FavoriteGroup{ID: id})
}

// DeleteGroupAndUngroup performs the metadata reassignment and group removal
// in one Bolt transaction so a failed delete cannot leave a half-ungrouped
// sidebar state.
func (f favoritesBackend) DeleteGroupAndUngroup(userID uint, id string) error {
	tx, err := f.db.Begin(true)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var group FavoriteGroup
	if err := tx.One("ID", id, &group); err != nil {
		if errors.Is(err, storm.ErrNotFound) {
			return favorites.ErrNotExist
		}
		return err
	}
	if group.UserID != userID {
		if group.UserID != 0 {
			return favorites.ErrNotExist
		}
		group.UserID = userID
		if err := tx.Update(&group); err != nil {
			return err
		}
	}

	var records []*Favorite
	if err := tx.Find("UserID", userID, &records); err != nil && !errors.Is(err, storm.ErrNotFound) {
		return err
	}
	for _, record := range records {
		if record.GroupID != id {
			continue
		}
		if err := tx.UpdateField(&Favorite{ID: record.ID}, "GroupID", ""); err != nil {
			return err
		}
	}
	if err := tx.DeleteStruct(&FavoriteGroup{ID: id}); err != nil {
		return err
	}
	return tx.Commit()
}

func (f favoritesBackend) DeleteByPath(path string) error {
	var records []*Favorite
	if err := f.db.All(&records); err != nil && !errors.Is(err, storm.ErrNotFound) {
		return err
	}
	for _, favorite := range records {
		if favorite.Path == path {
			return f.db.DeleteStruct(favorite)
		}
	}
	return nil
}

func newFavoriteRecord(favorite *favorites.Favorite) *Favorite {
	return &Favorite{
		ID: favorite.ID, UserID: favorite.UserID, Path: favorite.Path,
		Name: favorite.Name, GroupID: favorite.GroupID,
		AddedAt: favorite.AddedAt, Order: favorite.Order,
	}
}

func (favorite *Favorite) domain() *favorites.Favorite {
	return &favorites.Favorite{
		ID: favorite.ID, UserID: favorite.UserID, Path: favorite.Path,
		Name: favorite.Name, GroupID: favorite.GroupID,
		AddedAt: favorite.AddedAt, Order: favorite.Order,
	}
}

func favoriteDomains(records []*Favorite) []*favorites.Favorite {
	result := make([]*favorites.Favorite, len(records))
	for index, record := range records {
		result[index] = record.domain()
	}
	return result
}

func newFavoriteGroupRecord(group *favorites.FavoriteGroup) *FavoriteGroup {
	return &FavoriteGroup{
		ID: group.ID, UserID: group.UserID, Name: group.Name,
		Order: group.Order, Color: group.Color,
	}
}

func (group *FavoriteGroup) domain() *favorites.FavoriteGroup {
	return &favorites.FavoriteGroup{
		ID: group.ID, UserID: group.UserID, Name: group.Name,
		Order: group.Order, Color: group.Color,
	}
}
