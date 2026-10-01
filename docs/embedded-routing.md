# Embedded route policy

Selected Tailscale traffic must use the embedded userspace network. The locked SDK's generic `Server.Dial` delegates to `UserDial`, which may choose a system socket for destinations outside tailnet routes. It is therefore insufficient as the only enforcement boundary.

The client now classifies the destination with `UserDialPlan`, requires ownership by the embedded netstack, and invokes `NetstackDialTCP` with the returned IP/port. It never passes the hostname to a second general dial. Route withdrawal after classification may fail the request, but cannot redirect it to the system LAN. HTTP Host and TLS certificate validation retain the original service hostname.

This adapter uses `Server.Sys`, an explicitly unstable SDK API. Its use is isolated in `core/tailnet/node.go` and locked to tsnet 1.102.5. Dependency upgrades require source review of route classification and netstack-only dialing, compiler/native checks and the routing regressions; no reflection or unsafe access is used.

Tests cover a reachable LAN destination denied by route policy, a resolved overlay IP with original TLS/HTTP host, and cancellation before resolution. These are controlled fixtures. Real node approval, approved 100/subnet routes, external mobile/Wi-Fi, direct/DERP and lifecycle proof still require devices and enrollment resources.

Pinned primary sources: [tsnet](https://github.com/tailscale/tailscale/blob/v1.102.5/tsnet/tsnet.go), [tsdial](https://github.com/tailscale/tailscale/blob/v1.102.5/net/tsdial/tsdial.go).
