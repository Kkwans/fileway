package tailnet

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"tailscale.com/ipn"
)

func TestEncryptedNodeIdentitySurvivesRestartWithoutPlaintext(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node.state")
	key := bytes.Repeat([]byte{7}, 32)
	s, err := NewEncryptedStore(path, key)
	if err != nil {
		t.Fatal(err)
	}
	secret := []byte("private-node-key-and-identity")
	if err := s.WriteState("machine", secret); err != nil {
		t.Fatal(err)
	}
	disk, _ := os.ReadFile(path)
	if bytes.Contains(disk, secret) || bytes.Contains(disk, []byte("machine")) {
		t.Fatal("identity written as plaintext")
	}
	r, err := NewEncryptedStore(path, key)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := r.ReadState("machine")
	if err != nil || !bytes.Equal(actual, secret) {
		t.Fatal(actual, err)
	}
	actual[0] = 'x'
	actual, _ = r.ReadState("machine")
	if !bytes.Equal(actual, secret) {
		t.Fatal("caller mutated persisted identity")
	}
	before := append([]byte(nil), disk...)
	if _, err := NewEncryptedStore(path, bytes.Repeat([]byte{9}, 32)); err == nil {
		t.Fatal("wrong key opened state")
	}
	after, _ := os.ReadFile(path)
	if !bytes.Equal(before, after) {
		t.Fatal("failed unlock replaced identity")
	}
	disk[len(disk)-1] ^= 1
	os.WriteFile(path, disk, 0600)
	if _, err := NewEncryptedStore(path, key); err == nil {
		t.Fatal("tampered ciphertext accepted")
	}
}

func TestNodeStateConcurrentUpdatesAndDeletion(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node.state")
	s, _ := NewEncryptedStore(path, bytes.Repeat([]byte{1}, 32))
	var group sync.WaitGroup
	for _, id := range []ipn.StateKey{"one", "two", "three"} {
		id := id
		group.Add(1)
		go func() {
			defer group.Done()
			if err := s.WriteState(id, []byte(id)); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	r, _ := NewEncryptedStore(path, bytes.Repeat([]byte{1}, 32))
	for _, id := range []ipn.StateKey{"one", "two", "three"} {
		v, err := r.ReadState(id)
		if err != nil || string(v) != string(id) {
			t.Fatal(id, err)
		}
	}
	if err := r.WriteState("two", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := r.ReadState("two"); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatal("deleted state remained", err)
	}
}
