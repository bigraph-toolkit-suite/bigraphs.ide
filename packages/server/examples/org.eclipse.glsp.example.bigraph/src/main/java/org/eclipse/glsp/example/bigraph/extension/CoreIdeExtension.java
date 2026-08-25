package org.eclipse.glsp.example.bigraph.extensions;

import java.util.List;

import org.eclipse.glsp.example.bigraph.model.ModelVariant;

/**
 * Built-in "core" extension that declares the classic {@code bigraph}
 * diagram variant. It owns no private state, contributes no extra
 * handlers, and adds no palette items — its sole purpose is to make
 * the canonical bigraph variant visible to the
 * {@link ModelVariantRegistry} so the rest of the IDE can treat
 * "bigraph" the same way it treats every other plug-in variant
 * (single source of truth, no hard-coded strings).
 *
 * <p>The actual bigraph editing capabilities (action / operation
 * handlers, tool palette, signature, …) are wired by
 * {@code BigraphXMIDiagramModule} and the existing core
 * infrastructure; this class only carries the variant metadata.</p>
 */
public final class CoreIdeExtension implements IdeExtension {

    /** Canonical id of the classic bigraph variant. */
    public static final String BIGRAPH_VARIANT_ID = "bigraph";

    private static final ModelVariant BIGRAPH_VARIANT = ModelVariant.builder()
            .id(BIGRAPH_VARIANT_ID)
            .displayName("Bigraph")
            .description("Classic bigraph diagram (place graph + link graph)")
            .iconCodicon("symbol-structure")
            .build();

    @Override public String getId()   { return BIGRAPH_VARIANT_ID; }
    @Override public String getName() { return "Core Bigraph"; }

    @Override
    public List<ModelVariant> getSupportedModelVariants() {
        return List.of(BIGRAPH_VARIANT);
    }
}
