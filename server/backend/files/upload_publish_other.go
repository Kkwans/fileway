//go:build !linux && !windows

package files

import "os"

func publishUploadNative(from, to string, replace bool) error {
	if replace {
		return os.Rename(from, to)
	}
	if err := os.Link(from, to); err != nil {
		return err
	}
	return os.Remove(from)
}
