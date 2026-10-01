package tailnet

import (
	"bytes"
	"context"
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
