package tailnet

import (
	"errors"
	"net"
	"sync"

	"tailscale.com/net/netmon"
)

// Android supplies interface addresses through supported platform APIs. Go's
// net.Interfaces uses netlink, forbidden to apps targeting Android API 30+.
type PlatformInterface struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Up        bool     `json:"up"`
	Loopback  bool     `json:"loopback"`
	Multicast bool     `json:"multicast"`
	Addresses []string `json:"addresses"`
}

type PlatformNetwork struct {
	Interfaces       []PlatformInterface `json:"interfaces"`
	DefaultInterface string              `json:"defaultInterface"`
	DefaultGateway   string              `json:"defaultGateway"`
}

var platformSnapshot struct {
	sync.RWMutex
	ready      bool
	interfaces []netmon.Interface
}

func platformInterfaces() ([]netmon.Interface, error) {
	platformSnapshot.RLock()
	defer platformSnapshot.RUnlock()
	if !platformSnapshot.ready {
		return nil, errors.New("android network snapshot unavailable")
	}
	result := make([]netmon.Interface, len(platformSnapshot.interfaces))
	for i, source := range platformSnapshot.interfaces {
		iface := *source.Interface
		result[i] = netmon.Interface{Interface: &iface, AltAddrs: make([]net.Addr, len(source.AltAddrs))}
		for j, address := range source.AltAddrs {
			value := address.(*net.IPNet)
			result[i].AltAddrs[j] = &net.IPNet{IP: append(net.IP(nil), value.IP...), Mask: append(net.IPMask(nil), value.Mask...)}
		}
	}
	return result, nil
}

func UpdatePlatformNetwork(snapshot PlatformNetwork) error {
	if len(snapshot.Interfaces) > 128 {
		return errors.New("invalid android network snapshot")
	}
	interfaces := make([]netmon.Interface, 0, len(snapshot.Interfaces))
	for _, source := range snapshot.Interfaces {
		if source.Name == "" || source.Index <= 0 || source.MTU < 0 || len(source.Addresses) > 128 {
			return errors.New("invalid android network interface")
		}
		flags := net.Flags(0)
		if source.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if source.Loopback {
			flags |= net.FlagLoopback
		}
		if source.Multicast {
			flags |= net.FlagMulticast
		}
		iface := netmon.Interface{Interface: &net.Interface{Name: source.Name, Index: source.Index, MTU: source.MTU, Flags: flags}, AltAddrs: make([]net.Addr, 0, len(source.Addresses))}
		for _, value := range source.Addresses {
			ip, subnet, err := net.ParseCIDR(value)
			if err != nil {
				return errors.New("invalid android interface address")
			}
			// The host address is required; ParseCIDR's network address loses it.
			subnet.IP = ip
			iface.AltAddrs = append(iface.AltAddrs, subnet)
		}
		interfaces = append(interfaces, iface)
	}
	platformSnapshot.Lock()
	platformSnapshot.ready = true
	platformSnapshot.interfaces = interfaces
	updateDefaultRoute(snapshot.DefaultInterface, snapshot.DefaultGateway)
	platformSnapshot.Unlock()
	return nil
}
