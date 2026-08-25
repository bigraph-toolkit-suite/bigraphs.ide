package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

/**
 * The single {@link EdgeCreationChecker} bound with GLSP (via
 * {@code BigraphXMIDiagramModule#bindEdgeCreationChecker()}).
 *
 * <p>Routes live edge-draw validation to the extension that owns the
 * diagram's currently active variant: if that extension contributes a
 * checker through {@link IdeExtension#getEdgeCreationChecker()}, the
 * request is delegated to it; otherwise the edge is considered valid.
 * Default-allow deliberately mirrors GLSP's own behaviour when no
 * checker is bound at all (see {@code RequestCheckEdgeActionHandler}),
 * so core dynamic edge types ({@code bigraph:link}, {@code hyperedge},
 * {@code outer-connection}, …) keep working unchanged in the classic
 * bigraph variant.</p>
 *
 * <p>This dispatcher exists so that no extension ever needs to claim
 * the global {@link EdgeCreationChecker} Guice slot for itself — doing
 * so would both conflict with other extensions and leak variant-specific
 * validation into foreign variants.</p>
 *
 * @see IdeExtension#getEdgeCreationChecker()
 * @see VariantGate#activeOwner()
 */
@Singleton
public class ExtensionAwareEdgeCreationChecker implements EdgeCreationChecker {

    private final VariantGate variantGate;
    private final Injector injector;

    @Inject
    public ExtensionAwareEdgeCreationChecker(final VariantGate variantGate, final Injector injector) {
        this.variantGate = Objects.requireNonNull(variantGate, "variantGate");
        this.injector = Objects.requireNonNull(injector, "injector");
    }

    @Override
    public boolean isValidSource(final String edgeType, final GModelElement sourceElement) {
        return activeVariantChecker()
                .map(checker -> checker.isValidSource(edgeType, sourceElement))
                .orElse(true);
    }

    @Override
    public boolean isValidTarget(final String edgeType,
                                 final GModelElement sourceElement,
                                 final GModelElement targetElement) {
        return activeVariantChecker()
                .map(checker -> checker.isValidTarget(edgeType, sourceElement, targetElement))
                .orElse(true);
    }

    /**
     * Resolves the checker contributed by the active variant's owning
     * extension, or {@link Optional#empty()} when the owner contributes
     * none (default-allow).
     */
    private Optional<EdgeCreationChecker> activeVariantChecker() {
        return variantGate.activeOwner()
                .flatMap(IdeExtension::getEdgeCreationChecker)
                .map(injector::getInstance);
    }
}
