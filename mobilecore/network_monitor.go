package mobilecore

import "sync"

type networkEventInjector interface {
	InjectEvent()
}

var networkInjectorState struct {
	sync.RWMutex
	generation uint64
	injector   networkEventInjector
}

// installNetworkEventInjector publishes the exact netmon used by the active
// tsnet server. The returned cleanup cannot clear a newer server's monitor.
func installNetworkEventInjector(injector networkEventInjector) func() {
	networkInjectorState.Lock()
	networkInjectorState.generation++
	generation := networkInjectorState.generation
	networkInjectorState.injector = injector
	networkInjectorState.Unlock()
	return func() {
		networkInjectorState.Lock()
		defer networkInjectorState.Unlock()
		if networkInjectorState.generation == generation {
			networkInjectorState.injector = nil
		}
	}
}

func injectNetworkEvent() {
	networkInjectorState.RLock()
	injector := networkInjectorState.injector
	networkInjectorState.RUnlock()
	if injector != nil {
		injector.InjectEvent()
	}
}
