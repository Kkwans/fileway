//go:build !linux

package files

import "github.com/spf13/afero"

func fileIdentity(afero.Fs, string) *Identity { return nil }
func identityModeIsRegular(uint32) bool       { return false }
