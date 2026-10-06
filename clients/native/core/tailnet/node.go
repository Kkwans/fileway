package tailnet

import (
	"context"
	"errors"
	"net"
	"net/http"
	"path/filepath"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

type Connection struct {
	IP      string `json:"ip"`
	Mode    string `json:"mode"`
	TxBytes int64  `json:"txBytes"`
	RxBytes int64  `json:"rxBytes"`
}
type Status struct {
	State         string       `json:"state"`
	Authenticated bool         `json:"authenticated"`
	AuthURL       string       `json:"authUrl,omitempty"`
	IPs           []string     `json:"ips,omitempty"`
	AcceptSubnets bool         `json:"acceptSubnets"`
	Health        []string     `json:"health,omitempty"`
	Connections   []Connection `json:"connections,omitempty"`
}

type Node struct {
	mu        sync.Mutex
	server    *tsnet.Server
	local     *local.Client
	attempted bool
	closed    bool
	newServer func() *tsnet.Server
}

func NewNode(dir, hostname string, key []byte) (*Node, error) {
	if !filepath.IsAbs(dir) || hostname == "" {
		return nil, errors.New("invalid embedded node configuration")
	}
	if err := preparePlatformNode(dir); err != nil {
		return nil, err
	}
	store, err := NewEncryptedStore(filepath.Join(dir, "node.state"), key)
	if err != nil {
		return nil, err
	}
	quiet := func(string, ...any) {}
	newServer := func() *tsnet.Server {
		return &tsnet.Server{Dir: dir, Hostname: hostname, Store: store, Logf: quiet, UserLogf: quiet}
	}
	return &Node{server: newServer(), newServer: newServer}, nil
}

func (n *Node) Start(ctx context.Context) (Status, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return Status{}, errors.New("embedded network is closed")
	}
	if ctx.Err() != nil {
		return Status{}, ctx.Err()
	}
	n.attempted = true
	if err := startEmbeddedServer(n.server); err != nil {
		// tsnet caches init errors with sync.Once. Retrying that instance can
		// never recover; retain the encrypted identity and create a new server.
		// Upstream Close dereferences Sys; an early directory error has no
		// system/resources yet and must not be passed to Close.
		if n.server.Sys() != nil {
			_ = n.server.Close()
		}
		n.server = n.newServer()
		n.attempted = false
		return Status{}, errors.New("cannot start embedded network")
	}
	lc, err := n.server.LocalClient()
	if err != nil {
		return Status{}, errors.New("cannot control embedded network")
	}
	n.local = lc
	if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{Prefs: ipn.Prefs{RouteAll: true, WantRunning: true}, RouteAllSet: true, WantRunningSet: true}); err != nil {
		return Status{}, errors.New("cannot enable approved subnet routes")
	}
	status, err := n.statusLocked(ctx)
	if err != nil {
		return Status{}, err
	}
	// Start is idempotent in tsnet. After an explicit logout, request a new
	// interactive flow rather than relying on the first initialization again.
	if status.State == "NeedsLogin" && status.AuthURL == "" {
		if err := lc.StartLoginInteractive(ctx); err != nil {
			return Status{}, errors.New("cannot open embedded login flow")
		}
		return n.statusLocked(ctx)
	}
	return status, nil
}

// Keep upstream initialization panics recoverable at the node boundary. The
// bridge's outer recovery alone would leave tsnet's initOnce irreversibly set.
func startEmbeddedServer(server *tsnet.Server) (err error) {
	defer func() {
		if recover() != nil {
			err = errors.New("embedded initialization failed")
		}
	}()
	return server.Start()
}

func (n *Node) Status(ctx context.Context) (Status, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return Status{State: "Closed"}, nil
	}
	if n.local == nil {
		return Status{State: "Configured"}, nil
	}
	return n.statusLocked(ctx)
}

func (n *Node) statusLocked(ctx context.Context) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	s, err := n.local.Status(ctx)
	if err != nil {
		return Status{}, errors.New("cannot read embedded network status")
	}
	p, err := n.local.GetPrefs(ctx)
	if err != nil {
		return Status{}, errors.New("cannot read subnet preferences")
	}
	result := Status{State: s.BackendState, AuthURL: s.AuthURL, AcceptSubnets: p.RouteAll, Health: s.Health}
	result.Authenticated = p.Persist != nil && p.Persist.UserProfile.ID != 0
	if !p.WantRunning {
		result.State = "Stopped"
	}
	if result.State != "NeedsLogin" {
		result.AuthURL = ""
	}
	for _, ip := range s.TailscaleIPs {
		result.IPs = append(result.IPs, ip.String())
	}
	for _, peer := range s.Peer {
		if peer.Active && len(peer.TailscaleIPs) > 0 {
			result.Connections = append(result.Connections, Connection{IP: peer.TailscaleIPs[0].String(), Mode: connectionMode(peer), TxBytes: peer.TxBytes, RxBytes: peer.RxBytes})
		}
	}
	return result, nil
}

// PlatformNetworkChanged wakes the userspace monitor after Android's callback.
func (n *Node) PlatformNetworkChanged() {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed || n.local == nil {
		return
	}
	if sys := n.server.Sys(); sys != nil {
		if monitor, ok := sys.NetMon.GetOK(); ok {
			monitor.InjectEvent()
		}
	}
}

func connectionMode(peer *ipnstate.PeerStatus) string {
	if peer.PeerRelay != "" {
		return "peer-relay"
	}
	if peer.CurAddr != "" {
		return "direct"
	}
	if peer.Relay != "" {
		return "derp"
	}
	return "unknown"
}

func (n *Node) Client(ctx context.Context) (*http.Client, error) {
	state, err := n.Status(ctx)
	if err != nil {
		return nil, err
	}
	if state.State != "Running" {
		return nil, errors.New("embedded network needs login or approval")
	}
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil, errors.New("embedded network is closed")
	}
	// Sys and these subsystem APIs are not stable. Their usage is isolated
	// here, locked to tsnet 1.102.5, and must be audited on dependency upgrades.
	sys := n.server.Sys()
	if sys == nil || !sys.IsNetstack() {
		n.mu.Unlock()
		return nil, errors.New("embedded userspace transport unavailable")
	}
	dialer, ok := sys.Dialer.GetOK()
	if !ok || dialer == nil {
		n.mu.Unlock()
		return nil, errors.New("embedded dialer unavailable")
	}
	strict := routeDialer{plan: dialer.UserDialPlan, owned: dialer.UseNetstackForIP, tcp: dialer.NetstackDialTCP}
	n.mu.Unlock()
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
			defer cancel()
			return strict.dial(ctx, network, address)
		},
		DisableCompression: true, ResponseHeaderTimeout: 15 * time.Second, TLSHandshakeTimeout: 10 * time.Second, IdleConnTimeout: 60 * time.Second,
	}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, nil
}

func (n *Node) Logout(ctx context.Context) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.local == nil {
		return nil
	}
	if err := n.local.Logout(ctx); err != nil {
		return errors.New("cannot log out embedded node")
	}
	return nil
}

func (n *Node) Stop(ctx context.Context) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.local == nil {
		return nil
	}
	_, err := n.local.EditPrefs(ctx, &ipn.MaskedPrefs{Prefs: ipn.Prefs{WantRunning: false}, WantRunningSet: true})
	if err != nil {
		return errors.New("cannot stop embedded node")
	}
	return nil
}

func (n *Node) Close() error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return nil
	}
	n.closed = true
	if n.attempted {
		return n.server.Close()
	}
	return nil
}
