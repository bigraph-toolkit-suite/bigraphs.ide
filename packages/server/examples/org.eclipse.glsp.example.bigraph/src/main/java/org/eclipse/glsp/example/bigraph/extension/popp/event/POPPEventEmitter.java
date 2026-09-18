package org.eclipse.glsp.example.bigraph.extension.popp.event;

import java.util.List;

public interface POPPEventEmitter {
    List<POPPEventListener> getListeners();

    default void addListener(final POPPEventListener listener) {
        getListeners().add(listener);
    }
    default void removeListener(final POPPEventListener listener) {
        getListeners().remove(listener);
    }
}
