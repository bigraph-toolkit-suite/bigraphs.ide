package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Single source of truth for the rule
 * <em>"an extension's operations and palette items are active iff the
 * diagram's currently active variant id is supported by that
 * extension"</em>.
 *
 * <p>This is the only place in the codebase that decides whether a
 * given handler / palette contribution is allowed. Handlers and the
 * tool-palette provider consult it at runtime; nothing else needs to
 * know about variant semantics.</p>
 */
@Singleton
public class VariantGate {

    private final IBigraphModelState modelState;
    private final ModelVariantRegistry variantRegistry;

    @Inject
    public VariantGate(IBigraphModelState modelState,
                       ModelVariantRegistry variantRegistry) {
        this.modelState = Objects.requireNonNull(modelState, "modelState");
        this.variantRegistry = Objects.requireNonNull(variantRegistry, "variantRegistry");
    }

    /** Active variant id of the diagram, or {@code null} if uninitialised. */
    public String activeVariantId() {
        return modelState.getActiveVariantId();
    }

    /** Is the given variant id the one currently shown/edited? */
    public boolean isActive(String variantId) {
        return variantId != null && variantId.equals(activeVariantId());
    }

    /**
     * Are this extension's contributions allowed under the current active
     * variant? Looks up the variant's owning extension in the registry
     * and compares it to {@code extension}.
     */
    public boolean isExtensionActive(IdeExtension extension) {
        if (extension == null) return false;
        String active = activeVariantId();
        if (active == null) {
            // Before initialise: be permissive so cold-start actions
            // (e.g. project bootstrap) still go through.
            return true;
        }
        return extension.supportsVariant(active);
    }

    /**
     * Convenience: are core/classic bigraph contributions allowed?
     * Equivalent to {@code isActive("bigraph")} but resolved through the
     * registry so a renamed core variant would still be picked up.
     */
    public boolean isBigraphActive() {
        return isActive(CoreIdeExtension.BIGRAPH_VARIANT_ID);
    }

    /**
     * Are <em>direct</em> bigraph mutations (add/delete/move nodes,
     * rename, compose, ...) permitted for the currently loaded file?
     *
     * <p>Two conditions must hold:</p>
     * <ol>
     *   <li>The user is looking at the bigraph variant ({@link #isBigraphActive()}).</li>
     *   <li>The file itself is a plain bigraph file — i.e. the
     *       persisted {@code modelType} is absent or
     *       {@code "bigraph"}. For extension-typed files (e.g.
     *       {@code modelType: "behavior-tree"}) the bigraph view is
     *       read-only: bypassing the BT semantics could produce a
     *       bigraph that no longer round-trips back to a valid BT.</li>
     * </ol>
     *
     * <p>Handlers that mutate the bigraph should consult this gate
     * (rather than {@link #isBigraphActive()}) before applying their
     * change. Pure read handlers can keep using {@code isBigraphActive}
     * to render the data either way.</p>
     */
    public boolean isBigraphEditingPermitted() {
        if (!isBigraphActive()) {
            return false;
        }
        return isPlainBigraphFile();
    }

    /**
     * Does the underlying file declare no specific variant (i.e. it's
     * a vanilla bigraph that doesn't belong to any extension)?
     */
    public boolean isPlainBigraphFile() {
        var meta = modelState.getMetaInformation();
        if (meta == null) {
            return true;
        }
        String persisted = meta.getModelType();
        return persisted == null
                || persisted.isBlank()
                || CoreIdeExtension.BIGRAPH_VARIANT_ID.equals(persisted);
    }

    /** Resolve the extension that owns the currently active variant. */
    public Optional<IdeExtension> activeOwner() {
        return variantRegistry.findOwner(activeVariantId());
    }

    /**
     * Does the extension that owns the <em>currently displayed</em> variant
     * want to block a client move/resize for {@code elementGModelId}?
     *
     * <p>Only the active variant's owner is consulted — e.g. on a
     * behavior-tree file viewed as bigraph, the core extension owns the
     * active variant and BT nodes are not vetoed (they are not even in
     * the active GModel root).</p>
     */
    public boolean activeVariantVetoesBoundsChange(String elementGModelId) {
        return activeOwner()
                .map(ext -> ext.vetoesGModelBoundsChange(elementGModelId, modelState))
                .orElse(false);
    }
}
