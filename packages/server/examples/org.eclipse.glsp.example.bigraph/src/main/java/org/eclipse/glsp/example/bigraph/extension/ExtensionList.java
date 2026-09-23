package org.eclipse.glsp.example.bigraph.extensions;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtension;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


/**
 * Global registry of all registered {@link IdeExtension}s.
 *
 * <p>The registry is populated at class-loading time. To add a new
 * extension, simply append it to the static initializer below — no core
 * code changes are required elsewhere.</p>
 *
 * <p>The diagram module iterates this list during Guice configuration to
 * automatically wire every extension's action/operation handlers and
 * private state.</p>
 */
public final class ExtensionList {

    private static final ExtensionList INSTANCE = new ExtensionList();

    public static ExtensionList getInstance() {
        return INSTANCE;
    }

    private final List<IdeExtension> extensions = new ArrayList<>();

    private ExtensionList() {
        // ---- Register all extensions here ----
        // Core first — declares the classic "bigraph" variant.
        register(new CoreIdeExtension());
        register(new POPPExtension());
    }

    public void register(IdeExtension extension) {
        if (extension == null) {
            throw new IllegalArgumentException("Extension cannot be null");
        }
        if (findById(extension.getId()).isPresent()) {
            throw new IllegalStateException(
                    "Extension already registered: " + extension.getId());
        }
        extensions.add(extension);
    }

    public void unregister(String id) {
        extensions.removeIf(e -> e.getId().equals(id));
    }

    public List<IdeExtension> getExtensions() {
        return Collections.unmodifiableList(extensions);
    }

    public Optional<IdeExtension> findById(String id) {
        return extensions.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    /**
     * Invokes {@link IdeExtension#readExtensionMeta} on every registered extension.
     */
    public void readExtensionMeta(final BigraphMetaInformation meta, final Injector injector) {
        for (IdeExtension extension : extensions) {
            try {
                extension.readExtensionMeta(meta, injector);
            } catch (RuntimeException ex) {
                org.apache.logging.log4j.LogManager.getLogger(ExtensionList.class)
                        .error("Extension {} failed to read meta section", extension.getId(), ex);
            }
        }
    }

    /**
     * Invokes {@link IdeExtension#writeExtensionMeta} on every registered extension.
     */
    public void writeExtensionMeta(final BigraphMetaInformation meta, final Injector injector) {
        for (IdeExtension extension : extensions) {
            try {
                extension.writeExtensionMeta(meta, injector);
            } catch (RuntimeException ex) {
                org.apache.logging.log4j.LogManager.getLogger(ExtensionList.class)
                        .error("Extension {} failed to write meta section", extension.getId(), ex);
            }
        }
    }

    /**
     * Delegates delete to the extension that owns the active variant.
     *
     * @return {@code true} when an extension handled the delete request
     */
    public boolean dispatchDeleteElements(final List<String> elementIds,
                                          final Injector injector,
                                          final VariantGate variantGate) {
        return variantGate.activeOwner()
                .map(ext -> ext.deleteElements(elementIds, injector))
                .orElse(false);
    }
}
