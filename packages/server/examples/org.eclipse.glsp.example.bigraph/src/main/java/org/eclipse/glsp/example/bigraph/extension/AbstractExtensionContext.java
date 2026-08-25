package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

/**
 * Convenience base class for {@link ExtensionContext} implementations.
 *
 * <p>Each extension subclasses this once, supplying its own
 * {@link ExtensionStateKey} and a factory for the initial state. The
 * resulting class is the only Guice-injectable type the extension's
 * handlers ever see — providing both the shared bigraph (read-only) and
 * its own state.</p>
 *
 * <p>Design pattern: Template Method — the lazy-initialization +
 * repository-lookup boilerplate lives here so each extension only
 * supplies its key and factory.</p>
 *
 * @param <TState> the concrete state type owned by the extension
 */
public abstract class AbstractExtensionContext<TState> implements ExtensionContext<TState> {

    private final IBigraphModelState bigraphState;
    private final ExtensionStateRepository repository;
    private final ExtensionStateKey<TState> stateKey;
    private final Supplier<TState> stateFactory;

    protected AbstractExtensionContext(IBigraphModelState bigraphState,
                                       ExtensionStateRepository repository,
                                       ExtensionStateKey<TState> stateKey,
                                       Supplier<TState> stateFactory) {
        this.bigraphState = Objects.requireNonNull(bigraphState);
        this.repository   = Objects.requireNonNull(repository);
        this.stateKey     = Objects.requireNonNull(stateKey);
        this.stateFactory = Objects.requireNonNull(stateFactory);
    }

    @Override
    public final IBigraphModelState getBigraphModelState() {
        return bigraphState;
    }

    @Override
    public final TState getOwnState() {
        return repository.getOrCreate(stateKey, stateFactory);
    }
}
