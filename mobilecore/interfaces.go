package mobilecore

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"tailscale.com/net/netmon"
)

type interfaceJSON struct {
	Name         string `json:"name"`
	Index        int    `json:"index"`
	MTU          int    `json:"mtu"`
	Up           bool   `json:"up"`
	Broadcast    bool   `json:"broadcast"`
	Loopback     bool   `json:"loopback"`
	PointToPoint bool   `json:"pointToPoint"`
	Multicast    bool   `json:"multicast"`
	Addrs        []struct {
		IP        string `json:"ip"`
		PrefixLen int    `json:"prefixLen"`
	} `json:"addrs"`
}

// parseInterfacesJSON mirrors the official tailscale-android network monitor
// seam. Kotlin obtains this data through java.net.NetworkInterface, avoiding
// Go's restricted net.Interfaces/netlink path on modern Android.
func parseInterfacesJSON(raw string) ([]netmon.Interface, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, nil
	}
	var input []interfaceJSON
	if err := json.Unmarshal([]byte(raw), &input); err != nil {
		return nil, fmt.Errorf("parse Android interfaces JSON: %w", err)
	}
	out := make([]netmon.Interface, 0, len(input))
	for _, in := range input {
		if in.Name == "" {
			continue
		}
		nif := netmon.Interface{Interface: &net.Interface{Name: in.Name, Index: in.Index, MTU: in.MTU}}
		if in.Up {
			nif.Flags |= net.FlagUp
		}
		if in.Broadcast {
			nif.Flags |= net.FlagBroadcast
		}
		if in.Loopback {
			nif.Flags |= net.FlagLoopback
		}
		if in.PointToPoint {
			nif.Flags |= net.FlagPointToPoint
		}
		if in.Multicast {
			nif.Flags |= net.FlagMulticast
		}
		for _, a := range in.Addrs {
			ip, err := netip.ParseAddr(a.IP)
			if err != nil {
				continue
			}
			bits := 128
			if ip.Is4() {
				bits = 32
			}
			parsed := net.IP(ip.AsSlice())
			if ip.Zone() != "" || a.PrefixLen < 0 || a.PrefixLen > bits {
				nif.AltAddrs = append(nif.AltAddrs, &net.IPAddr{IP: parsed, Zone: ip.Zone()})
				continue
			}
			nif.AltAddrs = append(nif.AltAddrs, &net.IPNet{IP: parsed, Mask: net.CIDRMask(a.PrefixLen, bits)})
		}
		out = append(out, nif)
	}
	return out, nil
}
