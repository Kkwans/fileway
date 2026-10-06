package tailnet

import (
	"errors"
	"os"
	"path/filepath"
	"tailscale.com/net/netmon"
)

func init() { netmon.RegisterInterfaceGetter(platformInterfaces) }

func updateDefaultRoute(name, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(name)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}

func preparePlatformNode(dir string) error {
	// Embedded Android processes have no usable HOME, cwd or /tmp. The
	// upstream sockstat logger calls LogsDir independently of Server.Dir.
	logs := filepath.Join(dir, "logs")
	if err := os.MkdirAll(logs, 0700); err != nil {
		return errors.New("cannot prepare embedded log storage")
	}
	if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
		return errors.New("cannot configure embedded log storage")
	}
	return nil
}
