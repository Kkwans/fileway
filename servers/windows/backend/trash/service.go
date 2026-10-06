package trash

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"reflect"
	"strconv"

	"github.com/spf13/afero"

	"github.com/Kkwans/nas-file-browser/backend/favorites"
	"github.com/Kkwans/nas-file-browser/backend/pathmeta"
	"github.com/Kkwans/nas-file-browser/backend/recent"
	"github.com/Kkwans/nas-file-browser/backend/tags"
)

const HiddenDirectory = ".nas-file-browser-trash"

var (
	ErrInvalidPath     = errors.New("invalid trash path")
	ErrFilesystemRoot  = errors.New("filesystem root cannot be moved to trash")
	ErrConflict        = errors.New("restore destination already exists")
	ErrInvalidConflict = errors.New("invalid restore conflict strategy")
	ErrUnavailable     = errors.New("trash item is not available")
)

type ConflictStrategy string

const (
	ConflictFail     ConflictStrategy = "fail"
	ConflictKeepBoth ConflictStrategy = "keep-both"
	ConflictReplace  ConflictStrategy = "replace"
	ConflictSkip     ConflictStrategy = "skip"
)

type RestoreResult struct {
	Path    string `json:"path"`
	Skipped bool   `json:"skipped"`
}

type Service struct {
	Fs        afero.Fs
	Records   *Storage
	Favorites *favorites.Storage
	Tags      *tags.Storage
	Recent    *recent.Storage
	DirMode   fs.FileMode
	OnMoved   func(*Item)
}

func (service *Service) Move(userID uint, ownerName, source string) (*Item, error) {
	source = cleanPath(source)
	if source == "/" || IsInternalPath(source) {
		return nil, ErrInvalidPath
	}
	info, err := service.Fs.Stat(source)
	if err != nil {
		return nil, err
	}
	item, err := NewItem(userID, ownerName, source, info.Name(), info.IsDir(), info.Size())
	if err != nil {
		return nil, err
	}
	root, err := service.storageRoot(source, info)
	if err != nil {
		return nil, err
	}
	item.StoredPath = path.Join(root, HiddenDirectory, strconv.FormatUint(uint64(userID), 10), item.ID, info.Name())
	if err := service.Fs.MkdirAll(path.Dir(item.StoredPath), 0o700); err != nil {
		return nil, err
	}
	if err := service.Records.Save(item); err != nil {
		service.removeItemDirectory(item)
		return nil, err
	}
	if err := service.Fs.Rename(source, item.StoredPath); err != nil {
		return nil, errors.Join(err, service.deleteRecordAndDirectory(item))
	}

	favoriteMutation, tagMutation, recentMutation, err := service.removeMetadata(source)
	if err != nil {
		return nil, service.rollbackMove(item, nil, nil, nil, err)
	}
	item.FavoriteSnapshots = favoriteMutation.DeletedSnapshot()
	item.TagSnapshots = tagMutation.UpdatedSnapshot()
	item.Status = StatusAvailable
	if err := service.Records.Update(item); err != nil {
		return nil, service.rollbackMove(item, favoriteMutation, tagMutation, recentMutation, err)
	}
	if item.IsDir && service.OnMoved != nil {
		service.OnMoved(item)
	}
	return item.Clone(), nil
}

func (service *Service) Restore(actorUserID uint, id string, admin bool, strategy ConflictStrategy) (*RestoreResult, error) {
	if _, err := service.Records.Get(actorUserID, id, admin); err != nil {
		return nil, err
	}
	service.Records.cancelSize(id)
	unlock := service.Records.lockItem(id)
	defer unlock()
	item, err := service.Records.Get(actorUserID, id, admin)
	if err != nil {
		return nil, err
	}
	if item.Status != StatusAvailable && item.Status != StatusFailed {
		return nil, ErrUnavailable
	}
	if item.SizeState == SizeCalculating {
		item.SizeState = SizeIncomplete
		if err := service.Records.Update(item); err != nil {
			return nil, err
		}
	}

	if _, err := service.Fs.Stat(item.StoredPath); err != nil {
		return nil, err
	}

	destination := item.OriginalPath
	var displaced *Item
	exists, err := afero.Exists(service.Fs, destination)
	if err != nil {
		return nil, err
	}
	if exists {
		switch strategy {
		case ConflictSkip:
			return &RestoreResult{Path: destination, Skipped: true}, nil
		case ConflictKeepBoth:
			destination, err = addVersionSuffix(destination, service.Fs)
			if err != nil {
				return nil, err
			}
		case ConflictReplace:
			displaced, err = service.Move(item.UserID, item.OwnerName, destination)
			if err != nil {
				return nil, fmt.Errorf("move conflicting destination to trash: %w", err)
			}
		case ConflictFail, "":
			return nil, ErrConflict
		default:
			return nil, ErrInvalidConflict
		}
	}

	item.Status = StatusRestoring
	item.LastError = ""
	if err := service.Records.Update(item); err != nil {
		return nil, service.restoreDisplaced(displaced, err)
	}
	if err := service.Fs.MkdirAll(path.Dir(destination), service.dirMode()); err != nil {
		return nil, service.restoreDisplaced(displaced, service.markAvailable(item, err))
	}
	if err := service.Fs.Rename(item.StoredPath, destination); err != nil {
		return nil, service.restoreDisplaced(displaced, service.markAvailable(item, err))
	}

	favoriteSnapshots := rewriteFavoriteSnapshots(item.FavoriteSnapshots, item.OriginalPath, destination)
	restoredFavorites, err := service.Favorites.RestoreStagedSnapshot(favoriteSnapshots)
	if err != nil {
		return nil, service.restoreDisplaced(displaced, service.rollbackRestore(item, destination, nil, err))
	}
	if err := service.Tags.RestoreRemovedSnapshot(item.TagSnapshots, item.OriginalPath, destination); err != nil {
		return nil, service.restoreDisplaced(displaced, service.rollbackRestore(item, destination, restoredFavorites, err))
	}
	if err := service.Records.Delete(actorUserID, item.ID, admin); err != nil {
		item.Status = StatusFailed
		item.LastError = fmt.Sprintf("restored content but failed to remove recycle-bin record: %v", err)
		return nil, errors.Join(err, service.Records.Update(item))
	}
	service.removeItemDirectory(item)
	return &RestoreResult{Path: destination}, nil
}

func (service *Service) DeletePermanent(actorUserID uint, id string, admin bool) error {
	if _, err := service.Records.Get(actorUserID, id, admin); err != nil {
		return err
	}
	service.Records.cancelSize(id)
	unlock := service.Records.lockItem(id)
	defer unlock()
	item, err := service.Records.Get(actorUserID, id, admin)
	if err != nil {
		return err
	}
	if err := service.Fs.RemoveAll(item.StoredPath); err != nil && !os.IsNotExist(err) {
		return err
	}
	if err := service.Records.Delete(actorUserID, id, admin); err != nil {
		return err
	}
	service.removeItemDirectory(item)
	return nil
}

func IsInternalPath(value string) bool {
	for _, segment := range pathSegments(value) {
		if segment == HiddenDirectory {
			return true
		}
	}
	return false
}

func (service *Service) storageRoot(source string, sourceInfo os.FileInfo) (string, error) {
	parent := path.Dir(source)
	parentInfo, err := service.Fs.Stat(parent)
	if err != nil {
		return "", err
	}
	sourceDevice, sourceDeviceOK := deviceNumber(sourceInfo)
	parentDevice, parentDeviceOK := deviceNumber(parentInfo)
	if sourceDeviceOK && parentDeviceOK && sourceDevice != parentDevice {
		return "", ErrFilesystemRoot
	}
	root := parent
	if !sourceDeviceOK || !parentDeviceOK {
		return root, nil
	}
	for root != "/" {
		candidate := path.Dir(root)
		candidateInfo, err := service.Fs.Stat(candidate)
		if err != nil {
			return "", err
		}
		candidateDevice, ok := deviceNumber(candidateInfo)
		if !ok || candidateDevice != sourceDevice {
			break
		}
		root = candidate
	}
	return root, nil
}

func (service *Service) removeMetadata(source string) (*favorites.PathMutation, *tags.PathMutation, *recent.PathMutation, error) {
	favoriteMutation, err := service.Favorites.RemovePathPrefix(source)
	if err != nil {
		return nil, nil, nil, err
	}
	tagMutation, err := service.Tags.RemovePathPrefix(source)
	if err != nil {
		return nil, nil, nil, errors.Join(err, service.Favorites.RestorePathMutation(favoriteMutation))
	}
	if service.Recent == nil {
		return favoriteMutation, tagMutation, nil, nil
	}
	recentMutation, err := service.Recent.RemovePathPrefix(source)
	if err != nil {
		return nil, nil, nil, errors.Join(
			err,
			service.Tags.RestorePathMutation(tagMutation),
			service.Favorites.RestorePathMutation(favoriteMutation),
		)
	}
	return favoriteMutation, tagMutation, recentMutation, nil
}

func (service *Service) rollbackMove(item *Item, favoriteMutation *favorites.PathMutation, tagMutation *tags.PathMutation, recentMutation *recent.PathMutation, cause error) error {
	var recentErr error
	if service.Recent != nil {
		recentErr = service.Recent.RestorePathMutation(recentMutation)
	}
	metadataErr := errors.Join(
		service.Favorites.RestorePathMutation(favoriteMutation),
		service.Tags.RestorePathMutation(tagMutation),
		recentErr,
	)
	renameErr := service.Fs.Rename(item.StoredPath, item.OriginalPath)
	if renameErr == nil {
		return errors.Join(cause, metadataErr, service.deleteRecordAndDirectory(item))
	}
	item.Status = StatusFailed
	item.LastError = errors.Join(cause, metadataErr, renameErr).Error()
	return errors.Join(cause, metadataErr, renameErr, service.Records.Update(item))
}

func (service *Service) rollbackRestore(item *Item, destination string, restoredFavorites []favorites.Favorite, cause error) error {
	for _, favorite := range restoredFavorites {
		cause = errors.Join(cause, service.Favorites.Delete(favorite.UserID, favorite.ID))
	}
	renameErr := service.Fs.Rename(destination, item.StoredPath)
	if renameErr != nil {
		item.Status = StatusFailed
		item.LastError = errors.Join(cause, renameErr).Error()
		return errors.Join(cause, renameErr, service.Records.Update(item))
	}
	return service.markAvailable(item, cause)
}

func (service *Service) restoreDisplaced(displaced *Item, cause error) error {
	if displaced == nil {
		return cause
	}
	if _, err := service.Restore(displaced.UserID, displaced.ID, true, ConflictFail); err != nil {
		return errors.Join(cause, fmt.Errorf("restore displaced conflict: %w", err))
	}
	return cause
}

func (service *Service) markAvailable(item *Item, cause error) error {
	item.Status = StatusAvailable
	item.LastError = cause.Error()
	return errors.Join(cause, service.Records.Update(item))
}

func (service *Service) deleteRecordAndDirectory(item *Item) error {
	err := service.Records.Delete(item.UserID, item.ID, true)
	service.removeItemDirectory(item)
	return err
}

func (service *Service) removeItemDirectory(item *Item) {
	_ = service.Fs.Remove(path.Dir(item.StoredPath))
}

func (service *Service) dirMode() fs.FileMode {
	if service.DirMode == 0 {
		return 0o750
	}
	return service.DirMode
}

func cleanPath(value string) string {
	return path.Clean("/" + value)
}

func pathSegments(value string) []string {
	cleaned := cleanPath(value)
	segments := make([]string, 0)
	for cleaned != "/" && cleaned != "." {
		directory, name := path.Split(cleaned)
		if name != "" {
			segments = append(segments, name)
		}
		cleaned = path.Clean(directory)
	}
	return segments
}

func deviceNumber(info os.FileInfo) (uint64, bool) {
	if info == nil || info.Sys() == nil {
		return 0, false
	}
	value := reflect.ValueOf(info.Sys())
	if value.Kind() == reflect.Pointer {
		if value.IsNil() {
			return 0, false
		}
		value = value.Elem()
	}
	if value.Kind() != reflect.Struct {
		return 0, false
	}
	field := value.FieldByName("Dev")
	if !field.IsValid() {
		return 0, false
	}
	switch field.Kind() {
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return field.Uint(), true
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return uint64(field.Int()), true
	default:
		return 0, false
	}
}

func addVersionSuffix(source string, afs afero.Fs) (string, error) {
	directory, name := path.Split(source)
	extension := path.Ext(name)
	base := name[:len(name)-len(extension)]
	for counter := 1; ; counter++ {
		candidate := path.Join(directory, fmt.Sprintf("%s(%d)%s", base, counter, extension))
		if _, err := afs.Stat(candidate); err != nil {
			if os.IsNotExist(err) {
				return candidate, nil
			}
			return "", err
		}
	}
}

func rewriteFavoriteSnapshots(snapshot []favorites.Favorite, from, to string) []favorites.Favorite {
	rewritten := append([]favorites.Favorite(nil), snapshot...)
	for index := range rewritten {
		if next, matched := pathmeta.Rewrite(rewritten[index].Path, from, to); matched {
			rewritten[index].Path = next
		}
	}
	return rewritten
}
