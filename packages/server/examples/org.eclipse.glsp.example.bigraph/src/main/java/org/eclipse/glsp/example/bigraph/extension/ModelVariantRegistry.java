package org.eclipse.glsp.example.bigraph.extensions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.eclipse.glsp.example.bigraph.model.ModelVariant;

import com.google.inject.Singleton;

/**
 * Aggregates all {@link ModelVariant}s declared by registered
 * {@link IdeExtension}s, and answers two kinds of queries used
 * throughout the IDE:
 *
 * <ol>
 *   <li><b>Discovery</b>: which variants are available in this build?
 *       (e.g. for the "new diagram" picker)</li>
 *   <li><b>Resolution</b>: which extension owns a given variant id?
 *       (used by the gating layer to decide whether a handler /
 *       palette item should be active)</li>
 * </ol>
 *
 * <p>The registry is populated eagerly at construction time from
 * {@link ExtensionList} and is therefore safe to consult both during
 * Guice configuration and at runtime.</p>
 */
@Singleton
public class ModelVariantRegistry {

    private final List<ModelVariant> variants;
    private final List<VariantOwner> ownership;

    public ModelVariantRegistry() {
        this(ExtensionList.getInstance().getExtensions());
    }

    /** Visible for testing. */
    public ModelVariantRegistry(List<IdeExtension> extensions) {
        List<ModelVariant> collectedVariants = new ArrayList<>();
        List<VariantOwner> collectedOwnership = new ArrayList<>();
        for (IdeExtension ext : extensions) {
            for (ModelVariant variant : ext.getSupportedModelVariants()) {
                collectedVariants.add(variant);
                collectedOwnership.add(new VariantOwner(variant, ext));
            }
        }
        this.variants  = Collections.unmodifiableList(collectedVariants);
        this.ownership = Collections.unmodifiableList(collectedOwnership);
    }

    /** All registered variants, in registration order. */
    public List<ModelVariant> getAll() {
        return variants;
    }

    public Optional<ModelVariant> findById(String variantId) {
        if (variantId == null) return Optional.empty();
        return variants.stream().filter(v -> variantId.equals(v.getId())).findFirst();
    }

    /** Returns the extension that declared {@code variantId}, if any. */
    public Optional<IdeExtension> findOwner(String variantId) {
        if (variantId == null) return Optional.empty();
        return ownership.stream()
                .filter(o -> variantId.equals(o.variant.getId()))
                .map(o -> o.extension)
                .findFirst();
    }

    public boolean isKnown(String variantId) {
        return findById(variantId).isPresent();
    }

    private static final class VariantOwner {
        final ModelVariant variant;
        final IdeExtension extension;
        VariantOwner(ModelVariant variant, IdeExtension extension) {
            this.variant = variant;
            this.extension = extension;
        }
    }
}
