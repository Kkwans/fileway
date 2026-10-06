package files

import "github.com/spf13/afero"

type Identity struct {
	DeviceMajor uint32 `json:"deviceMajor"`
	DeviceMinor uint32 `json:"deviceMinor"`
	Inode       uint64 `json:"inode"`
	Links       uint64 `json:"links"`
	Mode        uint32 `json:"mode"`
	UID         uint32 `json:"uid"`
	GID         uint32 `json:"gid"`
}

func FileIdentity(filesystem afero.Fs, path string) *Identity { return fileIdentity(filesystem, path) }
func (identity *Identity) IsRegular() bool {
	return identity != nil && identityModeIsRegular(identity.Mode)
}
func (identity *Identity) Same(other *Identity) bool {
	return identity != nil && other != nil && identity.DeviceMajor == other.DeviceMajor && identity.DeviceMinor == other.DeviceMinor && identity.Inode == other.Inode && identity.Links == other.Links && identity.Mode == other.Mode && identity.UID == other.UID && identity.GID == other.GID
}
