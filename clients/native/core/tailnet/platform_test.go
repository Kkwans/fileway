package tailnet

import (
	"net"
	"sync"
	"testing"
)

func TestPlatformSnapshotPreservesHostAddressesAndDoesNotExposeMutableState(t *testing.T) {
	input := PlatformNetwork{Interfaces: []PlatformInterface{{Name: "wlan0", Index: 2, MTU: 1500, Up: true, Addresses: []string{"192.0.2.7/24", "2001:db8::9/64"}}, {Name: "empty0", Index: 3}}}
	if err := UpdatePlatformNetwork(input); err != nil {
		t.Fatal(err)
	}
	got, err := platformInterfaces()
	if err != nil || len(got) != 2 {
		t.Fatal(got, err)
	}
	if got[0].Name != "wlan0" || !got[0].IsUp() {
		t.Fatal(got[0])
	}
	if got[0].AltAddrs[0].(*net.IPNet).IP.String() != "192.0.2.7" || got[0].AltAddrs[1].(*net.IPNet).IP.String() != "2001:db8::9" {
		t.Fatal(got[0].AltAddrs)
	}
	addrs, err := got[1].Addrs()
	if err != nil || len(addrs) != 0 {
		t.Fatal("empty snapshot must not use forbidden Go interface lookup", addrs, err)
	}
	got[0].Name = "changed"
	got[0].AltAddrs[0].(*net.IPNet).IP[0] = 0
	again, _ := platformInterfaces()
	if again[0].Name != "wlan0" || again[0].AltAddrs[0].(*net.IPNet).IP.String() != "192.0.2.7" {
		t.Fatal("consumer mutated stored snapshot")
	}
	input.Interfaces[0].Addresses[0] = "bad-address"
	if err := UpdatePlatformNetwork(input); err == nil {
		t.Fatal("accepted invalid CIDR")
	}
	again, _ = platformInterfaces()
	if again[0].Name != "wlan0" {
		t.Fatal("failed update damaged previous snapshot")
	}
}

func TestPlatformSnapshotConcurrentChangesAndNetworkLoss(t *testing.T) {
	var workers sync.WaitGroup
	for i := 0; i < 4; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 50; j++ {
				if err := UpdatePlatformNetwork(PlatformNetwork{Interfaces: []PlatformInterface{{Name: "test0", Index: 1, Addresses: []string{"192.0.2.1/32"}}}}); err != nil {
					t.Error(err)
				}
				if _, err := platformInterfaces(); err != nil {
					t.Error(err)
				}
			}
		}()
	}
	workers.Wait()
	if err := UpdatePlatformNetwork(PlatformNetwork{}); err != nil {
		t.Fatal(err)
	}
	if got, err := platformInterfaces(); err != nil || len(got) != 0 {
		t.Fatal("offline snapshot incorrectly retains old interfaces", got, err)
	}
}
