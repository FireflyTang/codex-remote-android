//go:build !android

package mobilecore

func installPlatformHooks(Platform) error   { return nil }
func platformNetworkChanged(string, string) {}
