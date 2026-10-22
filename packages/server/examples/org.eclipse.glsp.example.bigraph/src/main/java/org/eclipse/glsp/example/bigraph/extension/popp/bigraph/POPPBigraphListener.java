package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

/**
 * Reacts to structural changes of a {@link POPPBigraph}: the view mirror and the incremental coverage analyzer. The
 * counterpart of {@link org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEventListener} for the bigraph.
 */
@FunctionalInterface
public interface POPPBigraphListener {
    void onBigraphEvent(POPPBigraphEvent event);
}
