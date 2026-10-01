// Package bridge is the versioned, JSON control boundary shared by JNI and
// future desktop bindings. Video bytes never pass through this boundary.
package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/Kkwans/nas-file-browser-client/core/transport"
)

type Command struct {
	Op        string          `json:"op"`
	RequestID string          `json:"requestId"`
	Session   string          `json:"session"`
	BaseURL   string          `json:"baseUrl"`
	Token     string          `json:"token"`
	Username  string          `json:"username"`
	Password  string          `json:"password"`
	Method    string          `json:"method"`
	Endpoint  string          `json:"endpoint"`
	Body      json.RawMessage `json:"body"`
	Path      string          `json:"path"`
	WirePath  string          `json:"wirePath"`
	URL       string          `json:"url"`
}

type Envelope struct {
	OK     bool   `json:"ok"`
	Result any    `json:"result,omitempty"`
	Error  string `json:"error,omitempty"`
}

type Engine struct {
	mu      sync.Mutex
	broker  *transport.Broker
	pending map[string]context.CancelFunc
}

func (e *Engine) ready() (*transport.Broker, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.broker == nil {
		b, err := transport.New()
		if err != nil {
			return nil, errors.New("cannot start private media transport")
		}
		e.broker = b
	}
	if e.pending == nil {
		e.pending = make(map[string]context.CancelFunc)
	}
	return e.broker, nil
}

func (e *Engine) Call(data []byte) []byte {
	var c Command
	if len(data) > 1<<20 || json.Unmarshal(data, &c) != nil {
		return encode(Envelope{Error: "invalid native command"})
	}
	result, err := e.execute(c)
	if err != nil {
		return encode(Envelope{Error: err.Error()})
	}
	return encode(Envelope{OK: true, Result: result})
}

func encode(value Envelope) []byte {
	data, err := json.Marshal(value)
	if err != nil {
		return []byte(`{"ok":false,"error":"cannot encode native response"}`)
	}
	return data
}

func (e *Engine) execute(c Command) (any, error) {
	b, err := e.ready()
	if err != nil {
		return nil, err
	}
	if c.Op == "cancel" {
		e.mu.Lock()
		cancel := e.pending[c.RequestID]
		e.mu.Unlock()
		if cancel != nil {
			cancel()
		}
		return nil, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if c.RequestID != "" {
		e.mu.Lock()
		if _, found := e.pending[c.RequestID]; found {
			e.mu.Unlock()
			return nil, errors.New("request already running")
		}
		e.pending[c.RequestID] = cancel
		e.mu.Unlock()
		defer func() { e.mu.Lock(); delete(e.pending, c.RequestID); e.mu.Unlock() }()
	}
	switch c.Op {
	case "init":
		return map[string]int{"protocol": 1}, nil
	case "open":
		return b.Open(c.BaseURL, c.Token, nil)
	case "login":
		return b.Login(ctx, c.Session, c.Username, c.Password)
	case "request":
		return b.Request(ctx, c.Session, c.Method, c.Endpoint, c.Body)
	case "token":
		return b.Token(c.Session)
	case "lease":
		endpoint, err := transport.RawEndpoint(c.Path, c.WirePath)
		if err != nil {
			return nil, err
		}
		return b.Lease(c.Session, endpoint, true)
	case "asset":
		return b.Lease(c.Session, c.Endpoint, false)
	case "revoke":
		b.Revoke(c.URL)
		return nil, nil
	case "close_session":
		b.CloseSession(c.Session)
		return nil, nil
	case "shutdown":
		e.mu.Lock()
		if e.broker == b {
			e.broker = nil
		}
		for _, stop := range e.pending {
			stop()
		}
		e.mu.Unlock()
		return nil, b.Close()
	default:
		return nil, errors.New("unknown native command")
	}
}
