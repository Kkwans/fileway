package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxAPIResponse = 8 << 20

type Response struct {
	Status      int    `json:"status"`
	Body        string `json:"body"`
	ContentType string `json:"contentType"`
}

type Session struct {
	base    *url.URL
	client  *http.Client
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.RWMutex
	token   string
	renewMu sync.Mutex
}

type Lease struct {
	session  *Session
	endpoint string
	media    bool
	ctx      context.Context
	cancel   context.CancelFunc
}

// Broker binds source credentials to immutable sessions. The public HTTP
// surface consists solely of random, revocable loopback lease URLs.
type Broker struct {
	mu       sync.RWMutex
	sessions map[string]*Session
	leases   map[string]*Lease
	listener net.Listener
	server   *http.Server
	closed   bool
}

func New() (*Broker, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	b := &Broker{sessions: make(map[string]*Session), leases: make(map[string]*Lease), listener: ln}
	b.server = &http.Server{Handler: b, ReadHeaderTimeout: 10 * time.Second, MaxHeaderBytes: 16 << 10}
	go func() { _ = b.server.Serve(ln) }()
	return b, nil
}

func randomID() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func DirectClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyFromEnvironment, DialContext: (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext, ResponseHeaderTimeout: 15 * time.Second, IdleConnTimeout: 60 * time.Second, DisableCompression: true},
		// Credentials must never follow a redirect to another server.
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// Open creates a fresh session even for the same profile. Rebinding cannot
// silently change the server/account of an already issued media lease.
func (b *Broker) Open(baseURL, token string, client *http.Client) (string, error) {
	base, err := BaseURL(baseURL)
	if err != nil {
		return "", err
	}
	id, err := randomID()
	if err != nil {
		return "", err
	}
	if client == nil {
		client = DirectClient()
	}
	copyClient := *client
	copyClient.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithCancel(context.Background())
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		cancel()
		return "", errors.New("transport closed")
	}
	b.sessions[id] = &Session{base: base, client: &copyClient, token: token, ctx: ctx, cancel: cancel}
	return id, nil
}

func (b *Broker) session(id string) (*Session, error) {
	b.mu.RLock()
	s := b.sessions[id]
	b.mu.RUnlock()
	if s == nil {
		return nil, errors.New("session closed or unknown")
	}
	return s, nil
}

func combined(parent, root context.Context) (context.Context, func()) {
	ctx, cancel := context.WithCancel(parent)
	stop := context.AfterFunc(root, cancel)
	return ctx, func() { stop(); cancel() }
}

func (s *Session) currentToken() string { s.mu.RLock(); defer s.mu.RUnlock(); return s.token }

func (s *Session) do(ctx context.Context, method, endpoint string, body []byte, headers http.Header) (*http.Response, error) {
	u, err := Endpoint(s.base, endpoint)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, method, u.String(), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	for k, values := range headers {
		for _, v := range values {
			req.Header.Add(k, v)
		}
	}
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	if token := s.currentToken(); token != "" {
		req.Header.Set("X-Auth", token)
	}
	res, err := s.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("cannot connect to service") // no credential or raw target in public errors
	}
	return res, nil
}

func readResponse(res *http.Response) (Response, error) {
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, maxAPIResponse+1))
	if err != nil {
		return Response{}, errors.New("service response interrupted")
	}
	if len(data) > maxAPIResponse {
		return Response{}, errors.New("service response exceeds size limit")
	}
	return Response{Status: res.StatusCode, Body: string(data), ContentType: res.Header.Get("Content-Type")}, nil
}

func (b *Broker) Login(ctx context.Context, sessionID, username, password string) (Response, error) {
	s, err := b.session(sessionID)
	if err != nil {
		return Response{}, err
	}
	ctx, done := combined(ctx, s.ctx)
	defer done()
	body, _ := json.Marshal(map[string]string{"username": username, "password": password, "recaptcha": ""})
	res, err := s.do(ctx, http.MethodPost, "/api/login", body, nil)
	if err != nil {
		return Response{}, err
	}
	response, err := readResponse(res)
	if err != nil {
		return Response{}, err
	}
	if response.Status == http.StatusOK {
		token := strings.TrimSpace(response.Body)
		if strings.Count(token, ".") != 2 {
			return Response{}, errors.New("login did not return a JWT text token")
		}
		s.mu.Lock()
		s.token = token
		s.mu.Unlock()
	}
	return response, nil
}

func (s *Session) renew(ctx context.Context, previous string) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	s.renewMu.Lock()
	defer s.renewMu.Unlock()
	if s.currentToken() != previous {
		return nil
	}
	res, err := s.do(ctx, http.MethodPost, "/api/renew", nil, nil)
	if err != nil {
		return err
	}
	r, err := readResponse(res)
	if err != nil {
		return err
	}
	if r.Status != http.StatusOK || strings.Count(strings.TrimSpace(r.Body), ".") != 2 {
		return errors.New("session expired; sign in again")
	}
	s.mu.Lock()
	s.token = strings.TrimSpace(r.Body)
	s.mu.Unlock()
	return nil
}

func (b *Broker) Request(ctx context.Context, id, method, endpoint string, body []byte) (Response, error) {
	if method != "GET" && method != "PUT" && method != "POST" && method != "DELETE" {
		return Response{}, errors.New("unsupported method")
	}
	s, err := b.session(id)
	if err != nil {
		return Response{}, err
	}
	ctx, done := combined(ctx, s.ctx)
	defer done()
	previous := s.currentToken()
	res, err := s.do(ctx, method, endpoint, body, nil)
	if err != nil {
		return Response{}, err
	}
	needsRenew := res.Header.Get("X-Renew-Token") == "true"
	r, err := readResponse(res)
	if err == nil && needsRenew && previous != "" {
		if renewErr := s.renew(ctx, previous); renewErr != nil {
			return Response{}, renewErr
		}
	}
	return r, err
}

func (b *Broker) Token(id string) (string, error) {
	s, err := b.session(id)
	if err != nil {
		return "", err
	}
	return s.currentToken(), nil
}

func (b *Broker) Lease(id, endpoint string, media bool) (string, error) {
	s, err := b.session(id)
	if err != nil {
		return "", err
	}
	if _, err := Endpoint(s.base, endpoint); err != nil {
		return "", err
	}
	key, err := randomID()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(s.ctx)
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed || b.sessions[id] != s {
		cancel()
		return "", errors.New("session closed")
	}
	b.leases[key] = &Lease{session: s, endpoint: endpoint, media: media, ctx: ctx, cancel: cancel}
	return "http://" + b.listener.Addr().String() + "/stream/" + key, nil
}

func (b *Broker) Revoke(value string) {
	key := strings.TrimPrefix(value, "http://"+b.listener.Addr().String()+"/stream/")
	b.mu.Lock()
	l := b.leases[key]
	delete(b.leases, key)
	b.mu.Unlock()
	if l != nil {
		l.cancel()
	}
}

func (b *Broker) CloseSession(id string) {
	b.mu.Lock()
	s := b.sessions[id]
	delete(b.sessions, id)
	for key, l := range b.leases {
		if l.session == s {
			l.cancel()
			delete(b.leases, key)
		}
	}
	b.mu.Unlock()
	if s != nil {
		s.cancel()
		s.client.CloseIdleConnections()
	}
}

func (b *Broker) Close() error {
	b.mu.Lock()
	b.closed = true
	for _, s := range b.sessions {
		s.cancel()
		s.client.CloseIdleConnections()
	}
	for _, l := range b.leases {
		l.cancel()
	}
	b.sessions = make(map[string]*Session)
	b.leases = make(map[string]*Lease)
	b.mu.Unlock()
	return b.server.Close()
}

func (b *Broker) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !strings.HasPrefix(r.URL.Path, "/stream/") || r.URL.RawQuery != "" || r.Header.Get("Origin") != "" {
		http.Error(w, "unknown stream", http.StatusNotFound)
		return
	}
	if r.Host != b.listener.Addr().String() {
		http.Error(w, "invalid host", http.StatusBadRequest)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "read only", http.StatusMethodNotAllowed)
		return
	}
	b.mu.RLock()
	l := b.leases[strings.TrimPrefix(r.URL.Path, "/stream/")]
	b.mu.RUnlock()
	if l == nil || l.ctx.Err() != nil {
		http.Error(w, "stream closed", http.StatusGone)
		return
	}
	ctx, done := combined(r.Context(), l.ctx)
	defer done()
	headers := make(http.Header)
	for _, k := range []string{"Range", "If-Range", "If-Modified-Since", "If-None-Match"} {
		if v := r.Header.Get(k); v != "" {
			headers.Set(k, v)
		}
	}
	if r.Method == http.MethodHead {
		headers.Set("Range", "bytes=0-0")
	}
	previous := l.session.currentToken()
	res, err := l.session.do(ctx, http.MethodGet, l.endpoint, nil, headers)
	if err != nil {
		http.Error(w, "source unavailable", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()
	if res.Header.Get("X-Renew-Token") == "true" && previous != "" {
		if err := l.session.renew(ctx, previous); err != nil {
			http.Error(w, "sign in again", http.StatusUnauthorized)
			return
		}
	}
	if l.media && res.StatusCode == http.StatusAccepted {
		http.Error(w, "playback permission required", http.StatusForbidden)
		return
	}
	for _, k := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if v := res.Header.Get(k); v != "" {
			w.Header().Set(k, v)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	status := res.StatusCode
	if r.Method == http.MethodHead && status == http.StatusPartialContent {
		parts := strings.Split(res.Header.Get("Content-Range"), "/")
		if len(parts) == 2 {
			if n, err := strconv.ParseInt(parts[1], 10, 64); err == nil && n >= 0 {
				w.Header().Set("Content-Length", parts[1])
				w.Header().Del("Content-Range")
				status = http.StatusOK
			}
		}
	}
	w.WriteHeader(status)
	if r.Method == http.MethodHead {
		return
	}
	buf := make([]byte, 64<<10)
	_, _ = io.CopyBuffer(w, res.Body, buf)
}
