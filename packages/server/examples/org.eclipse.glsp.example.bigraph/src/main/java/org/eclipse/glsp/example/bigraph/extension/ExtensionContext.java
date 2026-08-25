package org.eclipse.glsp.example.bigraph.extensions;

import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;

/**
 * Narrow access surface that every extension's handlers see at runtime.
 *
 * <p>It exposes exactly two pieces of state:</p>
 * <ol>
 *   <li>The shared bigraph (read-only access via {@link IBigraphModelState}).</li>
 *   <li>This extension's own state, typed as {@code TState}.</li>
 * </ol>
 *
 * <p>The interface deliberately omits any way to enumerate or reach
 * other extensions' states. Combined with the
 * {@link ExtensionStateKey}-as-capability discipline, this makes
 * cross-extension state access structurally impossible.</p>
 *
 * <p>Design pattern: Facade — one tiny, intention-revealing API that
 * shields handlers from the underlying property bag and Guice plumbing.</p>
 *
 * @param <TState> the concrete state type owned by this extension
 */
public interface ExtensionContext<TState> {

    /** Shared bigraph + gmodel root (read-only contract for extensions). */
    IBigraphModelState getBigraphModelState();

    /** Convenience: direct access to the gmodel root. */
    default GModelRoot getGModelRoot() {
        return getBigraphModelState().getRoot();
    }

    /**
     * Returns this extension's own state, lazily initialized on first
     * access via the factory declared in the implementation.
     */
    TState getOwnState();
}
