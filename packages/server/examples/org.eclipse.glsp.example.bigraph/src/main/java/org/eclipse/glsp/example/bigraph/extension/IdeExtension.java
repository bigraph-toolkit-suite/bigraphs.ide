package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.example.bigraph.model.ModelVariant;
import org.eclipse.glsp.server.actions.ActionHandler;
import org.eclipse.glsp.server.features.toolpalette.PaletteItem;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;
import org.eclipse.glsp.server.operations.OperationHandler;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;

import com.google.inject.Binder;
import com.google.inject.Injector;

/**
 * Contract for an IDE extension that plugs into the Bigraph diagram.
 *
 * <p>An extension is a self-contained module that adds new modeling
 * capabilities (e.g. Behavior Trees, Petri Nets, ...) on top of the
 * core bigraph editor without requiring any per-extension changes in
 * the core.</p>
 *
 * <h2>Lifecycle &amp; storage discipline</h2>
 * <ol>
 *   <li>{@link #configure(Binder)} — binds the extension's own
 *       {@link ExtensionContext} implementation. The state itself is
 *       <em>not</em> bound here; it lives in the diagram-scoped
 *       {@link ExtensionStateRepository}, keyed by {@link #getStateKey()}.</li>
 *   <li>{@link #getActionHandlers()} / {@link #getOperationHandlers()}
 *       — auto-registered with GLSP at module configuration time.</li>
 *   <li>At first access, the extension's {@code ExtensionContext}
 *       lazily creates the state via the factory provided to
 *       {@link AbstractExtensionContext}.</li>
 * </ol>
 *
 * <h2>Access discipline</h2>
 * Each extension's handlers may freely:
 * <ul>
 *   <li>Read the shared bigraph via {@code IBigraphModelState}.</li>
 *   <li>Read and mutate <em>their own</em> state via {@code
 *       ExtensionContext.getOwnState()}.</li>
 * </ul>
 * Cross-extension state access is structurally impossible: keys are
 * private to each extension, and {@link ExtensionContext} surfaces only
 * the extension's own state.
 *
 * @see ExtensionList
 * @see ExtensionContext
 * @see ExtensionStateRepository
 */
public interface IdeExtension {

    /** Stable, machine-readable identifier (e.g. {@code "behavior-tree"}). */
    String getId();

    /** Human-readable display name. */
    String getName();

    /**
     * Capability token identifying this extension's state slot in the
     * central {@link ExtensionStateRepository}. By convention, the
     * extension declares this as a {@code public static final} field
     * and the framework never reaches into it — only the extension's
     * own {@link ExtensionContext} implementation does.
     *
     * <p>May return {@code null} for stateless extensions (e.g. the
     * built-in core) that have no extension-private domain model.</p>
     */
    default ExtensionStateKey<?> getStateKey() {
        return null;
    }

    /**
     * Diagram variants this extension knows how to handle. The variant
     * ids returned here are the canonical discriminators used both for
     * the {@code modelType} entry in the {@code .bigraph-meta} file and
     * for gating action handlers and tool-palette items at runtime.
     *
     * <p>Extensions may declare zero, one, or many variants.</p>
     */
    default List<ModelVariant> getSupportedModelVariants() {
        return Collections.emptyList();
    }

    /**
     * Convenience predicate: does this extension claim ownership of the
     * given variant id? The default implementation walks
     * {@link #getSupportedModelVariants()}.
     */
    default boolean supportsVariant(String variantId) {
        if (variantId == null) return false;
        for (ModelVariant v : getSupportedModelVariants()) {
            if (variantId.equals(v.getId())) return true;
        }
        return false;
    }

    /**
     * Tool-palette items contributed by this extension, evaluated only
     * when the diagram's active variant id is supported by this
     * extension. The default returns an empty list.
     */
    default List<PaletteItem> getPaletteContributions() {
        return Collections.emptyList();
    }

    /**
     * Custom action handler classes contributed by this extension.
     * Each is automatically registered with the GLSP action dispatcher.
     */
    default List<Class<? extends ActionHandler>> getActionHandlers() {
        return Collections.emptyList();
    }

    /**
     * Custom operation handler classes contributed by this extension.
     */
    default List<Class<? extends OperationHandler<?>>> getOperationHandlers() {
        return Collections.emptyList();
    }

    /**
     * GLSP shape type hints for this extension's GModel element types
     * (drop targets, reparenting, …). The core diagram configuration
     * merges these into {@code getShapeTypeHints()}.
     */
    default List<ShapeTypeHint> contributeShapeTypeHints(
            final Function<String, ShapeTypeHint> hintFactory) {
        return Collections.emptyList();
    }

    /**
     * GLSP edge type hints for this extension's GModel edge types (arc tools,
     * connection validation, …). The core diagram configuration merges these
     * into {@code getEdgeTypeHints()}.
     */
    default List<EdgeTypeHint> contributeEdgeTypeHints(
            final Function<String, EdgeTypeHint> hintFactory) {
        return Collections.emptyList();
    }

    /**
     * Optional edge-creation checker validating live edge drawing for this
     * extension's <em>dynamic</em> edge types (see
     * {@link #contributeEdgeTypeHints}).
     *
     * <p>The core binds a single {@code ExtensionAwareEdgeCreationChecker}
     * with GLSP and consults this hook only while the extension owns the
     * active variant; for all other variants GLSP's default-allow behaviour
     * applies. Extensions must therefore <strong>never</strong> bind
     * {@link EdgeCreationChecker} themselves in {@link #configure(Binder)} —
     * that global Guice slot belongs to the core.</p>
     *
     * <p>The returned class is resolved through the diagram-scoped
     * {@link Injector}, so it may inject the extension's own services
     * (e.g. its {@code ExtensionContext}).</p>
     */
    default Optional<Class<? extends EdgeCreationChecker>> getEdgeCreationChecker() {
        return Optional.empty();
    }

    /**
     * Optional strategy replacing the core evolution loop for runs this
     * extension claims (e.g. exhaustive exploration or operation replay).
     * Consulted by {@code EvolutionRunActionHandler} before every run; the
     * first registered extension whose hook
     * {@link EvolutionRunHook#claims(org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction) claims}
     * the action takes over.
     */
    default Optional<EvolutionRunHook> getEvolutionRunHook() {
        return Optional.empty();
    }

    /**
     * Hook for the extension to bind its private {@link ExtensionContext}
     * implementation with Guice.
     *
     * <p>Typical usage:</p>
     * <pre>
     *   binder.bind(BTExtensionContext.class).in(Singleton.class);
     * </pre>
     */
    default void configure(Binder binder) {
        // no-op by default
    }

    /**
     * Lifecycle hook fired by the core after a source model has been loaded,
     * once per file open. Called only on extensions that own the active
     * variant id (i.e. {@link #supportsVariant(String)} returns {@code true}
     * for {@code state.getActiveVariantId()}).
     *
     * <p>Extensions implement this to reconstruct their private domain model
     * from the shared bigraph — for example the BT extension rebuilds its
     * {@code BehaviorTree} via {@code BigraphToBehaviorTreeConverter}. After
     * this method returns, the extension's state MUST be consistent with the
     * loaded bigraph.</p>
     *
     * <p>The {@link Injector} is provided so the extension can resolve its
     * own Guice-managed services (e.g. its {@code ExtensionContext} or a
     * dedicated hydrator service) without the core needing to know about
     * extension-private types.</p>
     */
    default void onModelLoaded(IBigraphModelState state, Injector injector) {
        // no-op by default — stateless extensions need no hydration
    }

    /**
     * Lifecycle hook fired after {@link org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation}
     * has been loaded into the model state. Extensions read their opaque
     * {@link BigraphMetaInformation#getExtensionSection(String)} payload here.
     *
     * <p>Called before {@link #onModelLoaded} so extension-private state
     * reconstructed from the bigraph can rely on meta already being present.</p>
     */
    default void readExtensionMeta(BigraphMetaInformation meta, Injector injector) {
        // no-op by default
    }

    /**
     * Lifecycle hook fired before the {@code .bigraph-meta} file is written.
     * Extensions serialize their private meta into
     * {@link BigraphMetaInformation#setExtensionSection(String, String)} here.
     */
    default void writeExtensionMeta(BigraphMetaInformation meta, Injector injector) {
        // no-op by default
    }

    /**
     * Hook for GLSP delete operations when this extension owns the active
     * variant. Return {@code true} when {@code elementIds} were handled
     * (even if some ids were skipped).
     */
    default boolean deleteElements(List<String> elementIds, Injector injector) {
        return false;
    }

    /**
     * Lifecycle hook consulted by {@code CreateBigraphActionHandler} when
     * a brand-new bigraph file is being created for {@code variantId}.
     * Extensions return the {@link DynamicSignature} the file should
     * start with — for the BT extension that's the canonical BT signature
     * ({@code Seq}, {@code Sel}, {@code Act}, {@code Cond}).
     *
     * <p>Default returns {@link Optional#empty()}; in that case the core
     * falls back to an empty signature, matching the historical behaviour
     * for plain bigraph files.</p>
     */
    default Optional<DynamicSignature> getInitialSignature(String variantId) {
        return Optional.empty();
    }

    /**
     * Hook consulted by {@link org.eclipse.glsp.example.bigraph.extensions.VariantGate}
     * when this extension owns the <em>currently displayed</em> variant.
     * Extensions with fixed-layout views (e.g. behavior trees) return
     * {@code true} for element ids they must not move while their variant
     * is active.
     *
     * @param elementGModelId id of the element the client wants to move
     * @param state           shared diagram state
     * @return {@code true} to ignore the bounds change for this element
     */
    default boolean vetoesGModelBoundsChange(String elementGModelId,
                                             IBigraphModelState state) {
        return false;
    }

    /**
     * Hook consulted by {@link org.eclipse.glsp.example.bigraph.handler.BigraphChangeBoundsOperationHandler}
     * when the active variant belongs to this extension and the user drags or
     * resizes a GModel element. Implementations should update their private
     * domain state and patch the live GModel element; return {@code true} when
     * the bounds change was handled.
     */
    default boolean applyGModelBoundsChange(final String elementGModelId,
                                            final GPoint newPosition,
                                            final GDimension newSize,
                                            final IBigraphModelState state,
                                            final Injector injector) {
        return false;
    }
}
