package trash

import (
	"context"
	"errors"
	"io"
	"os"
	"path"
	"sync"

	"github.com/spf13/afero"
)

type itemLock struct {
	mu   sync.Mutex
	refs int
}

// Short-lived keyed locks serialize restore/delete with the final size write.
// Enumeration never holds a lock needed by a user action.
func (s *Storage) lockItem(id string) func() {
	s.mu.Lock()
	lock := s.locks[id]
	if lock == nil {
		lock = &itemLock{}
		s.locks[id] = lock
	}
	lock.refs++
	s.mu.Unlock()
	lock.mu.Lock()
	return func() {
		lock.mu.Unlock()
		s.mu.Lock()
		defer s.mu.Unlock()
		lock.refs--
		if lock.refs == 0 {
			delete(s.locks, id)
		}
	}
}

func (s *Storage) cancelSize(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if cancel := s.sizing[id]; cancel != nil {
		cancel()
	}
}

// ReserveSize is called only on deletion or an explicit request, never on list.
func (s *Storage) ReserveSize(userID uint, id string, admin bool, taskID string) (context.Context, func(), error) {
	unlock := s.lockItem(id)
	defer unlock()
	item, err := s.Get(userID, id, admin)
	if err != nil {
		return nil, nil, err
	}
	if !item.IsDir || item.Status != StatusAvailable {
		return nil, nil, ErrUnavailable
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sizing[id] != nil {
		return nil, nil, ErrUnavailable
	}
	ctx, cancel := context.WithCancel(context.Background())
	item.SizeState, item.SizeTaskID = SizeCalculating, taskID
	if err := s.Update(item); err != nil {
		cancel()
		return nil, nil, err
	}
	s.sizing[id] = cancel
	return ctx, func() { cancel(); s.mu.Lock(); delete(s.sizing, id); s.mu.Unlock() }, nil
}

// RecoverSizes changes metadata only. Interrupted scans require explicit retry.
func (s *Storage) RecoverSizes() error {
	items, err := s.List(0, true)
	if err != nil {
		return err
	}
	for _, item := range items {
		if item.SizeState == SizeCalculating {
			item.SizeState = SizeIncomplete
			if err := s.Update(item); err != nil {
				return err
			}
		}
	}
	return nil
}

func (service *Service) MeasureSize(ctx context.Context, userID uint, id, taskID string, report func(int, int64) error) (*Item, error) {
	item, err := service.Records.Get(userID, id, true)
	if err != nil {
		return nil, err
	}
	if item.SizeTaskID != taskID || item.Status != StatusAvailable {
		return nil, ErrUnavailable
	}
	var size int64
	var count int
	var scanErr error
	select {
	case service.Records.sizeSlots <- struct{}{}:
		size, count, scanErr = directorySize(ctx, service.Fs, item.StoredPath, report)
		<-service.Records.sizeSlots
	case <-ctx.Done():
		scanErr = ctx.Err()
	}
	unlock := service.Records.lockItem(id)
	defer unlock()
	current, err := service.Records.Get(userID, id, true)
	if err != nil {
		return nil, err
	}
	if current.SizeTaskID != taskID || current.Status != StatusAvailable {
		return nil, ErrUnavailable
	}
	current.Size = size
	current.SizeState = SizeAccurate
	if scanErr != nil {
		current.SizeState = SizeIncomplete
		if count == 0 && !errors.Is(scanErr, context.Canceled) {
			current.SizeState = SizeFailed
		}
	}
	if err := service.Records.Update(current); err != nil {
		return nil, err
	}
	return current, scanErr
}

func directorySize(ctx context.Context, fs afero.Fs, root string, report func(int, int64) error) (int64, int, error) {
	var total int64
	count := 0
	var firstErr error
	pending := []string{root}
	for len(pending) > 0 {
		if err := ctx.Err(); err != nil {
			return total, count, err
		}
		dir := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		lstat, ok := fs.(afero.Lstater)
		if !ok {
			return total, count, errors.New("filesystem cannot inspect links safely")
		}
		before, supportsLstat, err := lstat.LstatIfPossible(dir)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		if (!supportsLstat && before.Sys() != nil) || before.Mode()&os.ModeSymlink != 0 || !before.IsDir() {
			if firstErr == nil {
				firstErr = ErrInvalidPath
			}
			continue
		}
		file, err := fs.Open(dir)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		opened, statErr := file.Stat()
		if statErr != nil || (before.Sys() != nil && !os.SameFile(before, opened)) {
			_ = file.Close()
			if firstErr == nil {
				firstErr = ErrInvalidPath
			}
			continue
		}
		for {
			if err := ctx.Err(); err != nil {
				_ = file.Close()
				return total, count, err
			}
			entries, readErr := file.Readdir(128)
			for _, entry := range entries {
				if entry.Mode()&os.ModeSymlink != 0 {
					continue
				}
				if entry.IsDir() {
					pending = append(pending, path.Join(dir, entry.Name()))
				} else if entry.Mode().IsRegular() {
					total += entry.Size()
					count++
				}
			}
			if report != nil {
				if err := report(count, total); err != nil {
					_ = file.Close()
					return total, count, err
				}
			}
			if readErr != nil {
				if !errors.Is(readErr, io.EOF) && firstErr == nil {
					firstErr = readErr
				}
				break
			}
			if len(entries) == 0 {
				break
			}
		}
		if err := file.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return total, count, firstErr
}

// FailSize clears a reserved state when the worker could not be attached.
func (s *Storage) FailSize(id, taskID string) error {
	unlock := s.lockItem(id)
	defer unlock()
	item, err := s.Get(0, id, true)
	if err != nil {
		return err
	}
	if item.SizeTaskID != taskID || item.Status != StatusAvailable {
		return ErrUnavailable
	}
	item.SizeState = SizeFailed
	return s.Update(item)
}
