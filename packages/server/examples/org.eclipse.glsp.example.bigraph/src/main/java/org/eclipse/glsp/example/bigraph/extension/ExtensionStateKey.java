package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Objects;

/**
 * Typed, identity-based handle for an extension's state slot inside the
 * central {@code GModelState} property bag.
 *
 * <p>The key serves as a <em>capability token</em>: only code that holds
 * a reference to a specific {@code ExtensionStateKey} can read or write
 * the corresponding state via {@link ExtensionStateRepository}. By
 * convention, each extension declares its key as a {@code public static
 * final} field on its {@code Extension} class and exposes typed access
 * to it via its own {@link ExtensionContext} implementation. Other
 * extensions cannot access the state because they cannot reach the
 * key.</p>
 *
 * <p>Two keys are equal iff they share the same id — the type parameter
 * is for compile-time safety only and is erased at runtime.</p>
 *
 * @param <T> the type of the state object stored under this key
 */
public final class ExtensionStateKey<T> {

    private static final String STORAGE_PREFIX = "extension.";
    private static final String STORAGE_SUFFIX = ".state";

    private final String id;
    private final Class<T> type;

    private ExtensionStateKey(String id, Class<T> type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Creates a key for the given extension id and state class.
     *
     * @param extensionId stable extension id (e.g. {@code "behavior-tree"})
     * @param type        runtime class of the state object
     */
    public static <T> ExtensionStateKey<T> of(String extensionId, Class<T> type) {
        return new ExtensionStateKey<>(extensionId, type);
    }

    /** Stable extension id. */
    public String getId() { return id; }

    /** Runtime class of the state object (for safe cast). */
    public Class<T> getType() { return type; }

    /** Internal storage key used inside the {@code GModelState} property bag. */
    public String getStorageKey() {
        return STORAGE_PREFIX + id + STORAGE_SUFFIX;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExtensionStateKey
                && id.equals(((ExtensionStateKey<?>) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ExtensionStateKey[" + id + ", " + type.getSimpleName() + "]";
    }
}
