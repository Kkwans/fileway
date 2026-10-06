package tailnet

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"

	"tailscale.com/ipn/ipnstate"
)

func TestConfigurationDoesNotEnrollOrDial(t *testing.T) {
	n, err := NewNode(t.TempDir(), "nfb-test", bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	defer n.Close()
	s, err := n.Status(context.Background())
	if err != nil || s.State != "Configured" || s.AuthURL != "" || len(s.IPs) != 0 {
		t.Fatal(s, err)
	}
	if _, err := n.Client(context.Background()); err == nil {
		t.Fatal("client silently used system sockets before embedded login")
	}
}

func TestFailedInitializationCanRetryWithoutReplacingEncryptedIdentity(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "node")
	n, err := NewNode(dir, "nfb-test", bytes.Repeat([]byte{1}, 32))
	if err != nil {
		t.Fatal(err)
	}
	defer n.Close()
	// Fail before networking starts. A file cannot serve as a state directory.
	if err := os.WriteFile(dir, []byte("directory blocked"), 0600); err != nil {
		t.Fatal(err)
	}
	store := n.server.Store
	for i := 0; i < 2; i++ {
		previous := n.server
		if _, err := n.Start(context.Background()); err == nil {
			t.Fatal("accepted invalid state directory")
		}
		if n.server == previous || n.server.Store != store || n.local != nil || n.attempted {
			t.Fatal("failed startup retained sticky tsnet error or replaced identity")
		}
		if status, err := n.Status(context.Background()); err != nil || status.State != "Configured" || status.Authenticated {
			t.Fatal(status, err)
		}
	}
}

func TestPeerModeDoesNotMislabelRelayAsDirect(t *testing.T) {
	for _, c := range []struct {
		peer ipnstate.PeerStatus
		want string
	}{
		{ipnstate.PeerStatus{CurAddr: "peer.test:123"}, "direct"},
		{ipnstate.PeerStatus{PeerRelay: "relay.test:123:vni:1", CurAddr: "peer.test:123"}, "peer-relay"},
		{ipnstate.PeerStatus{Relay: "region"}, "derp"},
		{ipnstate.PeerStatus{}, "unknown"},
	} {
		if got := connectionMode(&c.peer); got != c.want {
			t.Fatal(got, c.want)
		}
	}
}
