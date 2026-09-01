package mobilecore

import "testing"

type countingInjector struct{ count int }

func (i *countingInjector) InjectEvent() { i.count++ }

func TestNetworkEventInjectorUsesActiveGeneration(t *testing.T) {
	first, second := new(countingInjector), new(countingInjector)
	clearFirst := installNetworkEventInjector(first)
	injectNetworkEvent()
	if first.count != 1 {
		t.Fatalf("first count=%d", first.count)
	}
	clearSecond := installNetworkEventInjector(second)
	clearFirst()
	injectNetworkEvent()
	if second.count != 1 {
		t.Fatalf("second count=%d", second.count)
	}
	clearSecond()
	injectNetworkEvent()
	if second.count != 1 {
		t.Fatalf("cleared injector count=%d", second.count)
	}
}
