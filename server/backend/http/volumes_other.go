//go:build !windows

package fbhttp

import (
	"context"
	"fmt"
)

// discoverWindowsVolumes is a stub outside Windows (NAS path uses volume* scan).
func discoverWindowsVolumes(_ context.Context) ([]Volume, error) {
	return nil, fmt.Errorf("当前平台不支持 Windows 多磁盘卷")
}
