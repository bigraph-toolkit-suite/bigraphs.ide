package org.eclipse.glsp.example.bigraph.extension.popp.event;

import java.util.List;

public abstract class POPPEventEmitter {
    protected List<POPPEventListener> listeners;

    protected List<POPPEventListener> getListeners() {
        return listeners;
    }

    public void addListener(final POPPEventListener listener) {
        getListeners().add(listener);
    }
    public void removeListener(final POPPEventListener listener) {
        getListeners().remove(listener);
    }

    protected void emitEvent(final POPPEvent event) {
        listeners.forEach(listener -> listener.onPOPPEvent(event));
    }
}
