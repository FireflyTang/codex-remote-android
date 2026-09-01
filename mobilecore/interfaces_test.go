package mobilecore

import (
	"net"
	"testing"
)

func TestParseInterfacesJSON(t *testing.T) {
	got, err := parseInterfacesJSON(`[{"name":"wlan0","index":7,"mtu":1500,"up":true,"broadcast":true,"multicast":true,"addrs":[{"ip":"192.168.1.9","prefixLen":24},{"ip":"fe80::1%wlan0","prefixLen":64},{"ip":"bad","prefixLen":0}]}]`)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].Name != "wlan0" || got[0].Flags&net.FlagUp == 0 {
		t.Fatalf("unexpected interface: %+v", got)
	}
	if len(got[0].AltAddrs) != 2 {
		t.Fatalf("addresses=%v", got[0].AltAddrs)
	}
}

func TestParseInterfacesJSONRejectsMalformedPayload(t *testing.T) {
	if _, err := parseInterfacesJSON(`{"name":"wlan0"}`); err == nil {
		t.Fatal("expected error")
	}
}
