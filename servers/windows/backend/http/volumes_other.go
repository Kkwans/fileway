//go:build !windows

package fbhttp

import (
	"context"
	"fmt"
)

// discoverWindowsVolumes is a stub outside Windows (NAS path uses volume* scan).
func discoverWindowsVolumes(_ context.Context) ([]Volume, error) {
	return nil, fmt.Errorf("Windows 多磁盘卷在当前平台不可用")
}
