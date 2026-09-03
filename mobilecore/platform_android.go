//go:build android

package mobilecore

import (
	"fmt"

	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

var androidPlatform Platform

func installPlatformHooks(p Platform) error {
	if p == nil {
		return fmt.Errorf("platform is nil")
	}
	androidPlatform = p
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return parseInterfacesJSON(androidPlatform.InterfacesJSON())
	})
	netns.SetAndroidBindToNetworkFunc(func(fd int) error {
		if !androidPlatform.BindSocketToNetwork(int32(fd)) {
			return fmt.Errorf("Android socket routing hook rejected fd %d", fd)
		}
		return nil
	})
	return nil
}

func platformNetworkChanged(iface, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(iface)
	netmon.UpdateLastKnownDefaultGateway(gateway)
	injectNetworkEvent()
}

var _ networkEventInjector = (*netmon.Monitor)(nil)
