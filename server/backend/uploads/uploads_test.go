package uploads

import (
	"errors"
	"sync"
	"testing"
	"time"
)

type memoryBackend struct {
	mutex sync.Mutex
	rows  map[string]*Session
}

func (b *memoryBackend) Get(id string) (*Session, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	row := b.rows[id]
	if row == nil {
		return nil, ErrNotExist
	}
	return row.Clone(), nil
}
func (b *memoryBackend) Save(row *Session) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	b.rows[row.ID] = row.Clone()
	return nil
}
func (b *memoryBackend) All() ([]*Session, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	result := []*Session{}
	for _, row := range b.rows {
		result = append(result, row.Clone())
	}
	return result, nil
}

func TestUploadBindingsSingleUseAndRetention(t *testing.T) {
	store := NewStorage(&memoryBackend{rows: map[string]*Session{}})
	now := time.Unix(12345, 0)
	row := &Session{ID: "owned", UserID: 1, WirePath: "/%FF/owned.bin", RootKey: "owned-root", Length: 8, State: Active}
	Touch(row, now)
	if err := store.Create(row); err != nil {
		t.Fatal(err)
	}
	if err := store.Create(row); !errors.Is(err, ErrConflict) {
		t.Fatal("ID reused", err)
	}
	if _, err := store.Get("owned", 2, row.WirePath, row.RootKey); !errors.Is(err, ErrNotExist) {
		t.Fatal("another user found session", err)
	}
	for _, input := range []struct{ wire, root string }{{"/other.bin", row.RootKey}, {row.WirePath, "other-root"}} {
		if _, err := store.Get("owned", 1, input.wire, input.root); !errors.Is(err, ErrConflict) {
			t.Fatal("session target rebound", err)
		}
	}
	if rows, _ := store.Due(now.Add(195 * time.Second)); len(rows) != 0 {
		t.Fatal("short idle incorrectly expired", rows)
	}
	if rows, _ := store.Due(now.Add(Retention)); len(rows) != 1 {
		t.Fatal("retention deadline missing", rows)
	}
	row.State = Completed
	if err := store.Save(row); err != nil {
		t.Fatal(err)
	}
	if rows, _ := store.Due(now.Add(Retention * 2)); len(rows) != 0 {
		t.Fatal("completed file eligible for cleanup")
	}
}
