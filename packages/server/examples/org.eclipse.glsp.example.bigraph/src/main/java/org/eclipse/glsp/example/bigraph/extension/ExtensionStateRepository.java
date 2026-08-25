package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.eclipse.glsp.server.model.GModelState;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Repository backed by the central {@link GModelState} property bag that
 * stores one piece of state per extension.
 *
 * <p>Each entry is identified by an {@link ExtensionStateKey} held
 * privately by the owning extension — see the class JavaDoc of
 * {@link ExtensionStateKey} for the access discipline.</p>
 *
 * <p>This is the <em>only</em> production storage location for extension
 * states. Extensions never hold mutable state as static fields or as
 * Guice singletons; they always go through this repository, which means
 * every diagram session gets its own isolated state — derived for free
 * from GLSP's per-session {@code GModelState}.</p>
 *
 * <p>Design pattern: Repository — encapsulates the property-bag idiom
 * behind a typed, key-driven API.</p>
 */
@Singleton
public class ExtensionStateRepository {

    private final GModelState modelState;

    @Inject
    public ExtensionStateRepository(GModelState modelState) {
        this.modelState = Objects.requireNonNull(modelState);
    }

    /**
     * Returns the state stored under {@code key}, or {@link Optional#empty()}
     * if it has not been initialized yet.
     */
    public <T> Optional<T> get(ExtensionStateKey<T> key) {
        return modelState.getProperty(key.getStorageKey(), key.getType());
    }

    /**
     * Returns the state stored under {@code key}, lazily creating it via
     * {@code factory} on first access.
     */
    public <T> T getOrCreate(ExtensionStateKey<T> key, Supplier<T> factory) {
        Optional<T> existing = get(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        T created = Objects.requireNonNull(factory.get(),
                "Extension state factory returned null for key " + key);
        modelState.setProperty(key.getStorageKey(), created);
        return created;
    }

    /** Stores the given state under {@code key}, replacing any previous value. */
    public <T> void set(ExtensionStateKey<T> key, T state) {
        modelState.setProperty(key.getStorageKey(), state);
    }

    /** Drops the state stored under {@code key}, if any. */
    public void clear(ExtensionStateKey<?> key) {
        modelState.clearProperty(key.getStorageKey());
    }
}
