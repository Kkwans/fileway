//go:build !linux && !windows

package files

func setCreatedTime(*FileInfo) {}
