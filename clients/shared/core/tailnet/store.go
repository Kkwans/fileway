package tailnet

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"tailscale.com/ipn"
)

var stateVersion = []byte("NFB1")

// EncryptedStore persists the entire node identity as authenticated ciphertext.
// Android supplies a random data key wrapped by its non-exportable Keystore key.
type EncryptedStore struct {
	mu     sync.Mutex
	path   string
	aead   cipher.AEAD
	values map[string][]byte
}

func NewEncryptedStore(path string, key []byte) (*EncryptedStore, error) {
	if len(key) != 32 || !filepath.IsAbs(path) {
		return nil, errors.New("invalid node storage configuration")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	s := &EncryptedStore{path: path, aead: aead, values: make(map[string][]byte)}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, errors.New("cannot read encrypted node state")
	}
	if len(data) < 4+aead.NonceSize()+aead.Overhead() || string(data[:4]) != string(stateVersion) {
		return nil, errors.New("invalid encrypted node state version")
	}
	plain, err := aead.Open(nil, data[4:4+aead.NonceSize()], data[4+aead.NonceSize():], stateVersion)
	if err != nil {
		return nil, errors.New("cannot unlock node state; original identity preserved")
	}
	if json.Unmarshal(plain, &s.values) != nil || s.values == nil {
		return nil, errors.New("invalid encrypted node state contents")
	}
	return s, nil
}

func (s *EncryptedStore) ReadState(id ipn.StateKey) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	value, ok := s.values[string(id)]
	if !ok {
		return nil, ipn.ErrStateNotExist
	}
	return append([]byte(nil), value...), nil
}

func (s *EncryptedStore) WriteState(id ipn.StateKey, value []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make(map[string][]byte, len(s.values)+1)
	for key, value := range s.values {
		next[key] = value
	}
	if value == nil {
		delete(next, string(id))
	} else {
		next[string(id)] = append([]byte(nil), value...)
	}
	plain, err := json.Marshal(next)
	if err != nil {
		return err
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	data := append(append([]byte(nil), stateVersion...), nonce...)
	data = s.aead.Seal(data, nonce, plain, stateVersion)
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return errors.New("cannot prepare private node storage")
	}
	file, err := os.CreateTemp(dir, ".node-state-")
	if err != nil {
		return err
	}
	name := file.Name()
	defer os.Remove(name)
	if err = file.Chmod(0600); err == nil {
		_, err = file.Write(data)
	}
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return errors.New("cannot save encrypted node state")
	}
	if err = os.Rename(name, s.path); err != nil {
		return errors.New("cannot replace encrypted node state")
	}
	s.values = next
	return nil
}

var _ ipn.StateStore = (*EncryptedStore)(nil)
