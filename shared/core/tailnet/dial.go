package tailnet

import (
	"context"
	"errors"
	"net"
	"net/netip"
)

// routeDialer deliberately has no system-network fallback. Keep the resolved
// IP from UserDialPlan: resolving the original hostname again would allow a
// DNS/route change to send credentials outside the selected embedded network.
type routeDialer struct {
	plan  func(context.Context, string, string) (netip.AddrPort, bool, error)
	owned func(netip.Addr) bool
	tcp   func(context.Context, netip.AddrPort) (net.Conn, error)
}

func (d routeDialer) dial(ctx context.Context, network, address string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if (network != "tcp" && network != "tcp4" && network != "tcp6") || d.plan == nil || d.owned == nil || d.tcp == nil {
		return nil, errors.New("embedded TCP transport unavailable")
	}
	target, viaTailnet, err := d.plan(ctx, network, address)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("cannot resolve embedded destination")
	}
	if !target.IsValid() || !viaTailnet || !d.owned(target.Addr()) {
		return nil, errors.New("destination is not an approved tailnet route")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	// Even if the route is removed after classification, this function only
	// reaches netstack. It cannot fall through to a reachable system LAN.
	return d.tcp(ctx, target)
}
