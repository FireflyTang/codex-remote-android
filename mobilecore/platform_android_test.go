//go:build android

package mobilecore

import "testing"

// This test is cross-compiled into the Android gate and can run on-device. It
// verifies that network_changed reaches the same injectable seam used by the
// tsnet monitor, in addition to updating Android route metadata.
func TestAndroidNetworkChangedInjectsEvent(t *testing.T) {
	injector := new(countingInjector)
	clear := installNetworkEventInjector(injector)
	defer clear()
	platformNetworkChanged("wlan0", "192.168.1.1")
	if injector.count != 1 {
		t.Fatalf("InjectEvent count=%d", injector.count)
	}
}
