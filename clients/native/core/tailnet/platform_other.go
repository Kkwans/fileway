//go:build !android

package tailnet

func updateDefaultRoute(_, _ string)     {}
func preparePlatformNode(_ string) error { return nil }
