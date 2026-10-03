package org.eclipse.glsp.example.bigraph.extension.popp.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class POPPEventEmitter {
    protected List<POPPEventListener> listeners = new CopyOnWriteArrayList<>(); // Prevent ConcurrentModificationException

    protected List<POPPEventListener> getListeners() {
        return listeners;
    }

    public void addListener(final POPPEventListener listener) {
        if (!getListeners().contains(listener)) getListeners().add(listener);
    }
    public void removeListener(final POPPEventListener listener) {
        getListeners().remove(listener);
    }

    protected void emitEvent(final POPPEvent event) {
        listeners.forEach(listener -> listener.onPOPPEvent(event));
    }
}
