package org.eclipse.glsp.example.bigraph.extension.popp;

import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphLoader;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.ScheduledRewritingCoverageAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPEdgeCreationChecker;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPTypeHints;
import org.eclipse.glsp.example.bigraph.extension.popp.handler.*;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.example.bigraph.extension.popp.validation.POPPValidationService;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateKey;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.example.bigraph.model.ModelVariant;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.actions.ActionHandler;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;
import org.eclipse.glsp.server.operations.OperationHandler;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The POPP editor as an IDE extension: contributes the POPP model variant, its handlers, type hints and Guice
 * bindings, loads the POPP model from the bigraph when a file is opened, and maps deletions, moves and the meta file
 * onto the domain model.
 */
public class POPPExtension implements IdeExtension {
    public static final ExtensionStateKey<POPPExtensionState> STATE_KEY =
            ExtensionStateKey.of("popp", POPPExtensionState.class);

    /** Variant id used as the {@code modelType} discriminator in the meta file. */
    public static final String POPP_VARIANT_ID = "popp";

    private static final ModelVariant POPP_VARIANT = ModelVariant.builder()
            .id(POPP_VARIANT_ID)
            .displayName("Problem-Oriented Project Planning")
            .description("Problem-Oriented Project Planning (encoded as a bigraph under the hood)")
            .iconCodicon("new-popp-model")
            .build();

    /** The coverage warm-up runs once per JVM: JIT and class loading are shared by all sessions. */
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean();

    @Override
    public String getId() {
        return STATE_KEY.getId();
    }

    @Override
    public String getName() {
        return POPP_VARIANT.getDisplayName();
    }


    @Override
    public ExtensionStateKey<?> getStateKey() {
        return STATE_KEY;
    }

    @Override
    public List<ModelVariant> getSupportedModelVariants() {
        return List.of(POPP_VARIANT);
    }

    @Override
    public List<Class<? extends ActionHandler>> getActionHandlers() {
        return List.of(
                SwitchDecompositionTypeHandler.class,
                CreateNodeOperationHandler.class,
                EditDescriptionNodeHandler.class,
                InspectionActionHandler.class,
                CoverageReportHandler.class,
                CoverageGapsHandler.class,
                ValidateModelHandler.class,
                ExportJsonHandler.class,
                ImportJsonHandler.class
        );
    }

    @Override
    public List<Class<? extends OperationHandler<?>>> getOperationHandlers() {
        return List.of(
                EdgeCreationHandler.class
        );
    }

    @Override
    public List<ShapeTypeHint> contributeShapeTypeHints(final Function<String, ShapeTypeHint> hintFactory) {
        return POPPTypeHints.shapeTypeHints(hintFactory);
    }

    @Override
    public List<EdgeTypeHint> contributeEdgeTypeHints(final Function<String, EdgeTypeHint> hintFactory) {
        return POPPTypeHints.edgeTypeHintList(hintFactory);
    }

    @Override
    public void configure(final Binder binder) {
        binder.bind(POPPExtensionContext.class).in(Singleton.class);
        binder.bind(POPPEdgeCreationChecker.class).in(Singleton.class);
        binder.bind(POPPGModelSynchronizer.class).in(Singleton.class);
        binder.bind(POPPBigraphSynchronizer.class).in(Singleton.class);
        binder.bind(POPPBigraphLoader.class).in(Singleton.class);
        binder.bind(POPPValidationService.class).in(Singleton.class);
    }

    @Override
    public void onModelLoaded(final IBigraphModelState state, final Injector injector) {
        injector.getInstance(POPPBigraphLoader.class).loadModelFromBigraph(state.getMutableBigraph());
        if (WARMED_UP.compareAndSet(false, true)) {
            Thread warmUp = new Thread(ScheduledRewritingCoverageAnalyzer::warmUp, "popp-warmup");
            warmUp.setDaemon(true);
            warmUp.start();
        }
    }


    @Override
    public Optional<Class<? extends EdgeCreationChecker>> getEdgeCreationChecker() {
        return Optional.of(POPPEdgeCreationChecker.class);
    }

    @Override
    public Optional<DynamicSignature> getInitialSignature(final String variantId) {
        if (POPP_VARIANT_ID.equals(variantId)) {
            return Optional.of(POPPBigraphSignature.create());
        }
        return Optional.empty();
    }

    @Override
    public boolean deleteElements(final List<String> elementIds, final Injector injector) {
        POPPModel model = injector.getInstance(POPPExtensionContext.class).getOwnState().getPoppModel();
        return model != null && deleteElements(model, elementIds);
    }

    /**
     * Deletes the POPP elements among {@code elementIds} (GModel ids): nodes, relation edges (the relation is removed)
     * and decomposition edges (the child leaves its parent and becomes a root). Edges are resolved against the domain
     * model, not the GModel, and before any node is deleted, so a selection of a node together with its edges works.
     * Ids that name nothing (any more) are skipped. Returns whether anything was deleted.
     */
    static boolean deleteElements(final POPPModel model, final List<String> elementIds) {
        POPPGModelFactory ids = new POPPGModelFactory();
        Set<String> wanted = new HashSet<>(elementIds);
        List<Relation> relations = model.getRelations().all().stream()
                .filter(r -> wanted.contains(ids.toRelationEdgeId(r))).toList();
        List<TreeNode<?>> detached = model.getAllNodes().stream()
                .filter(child -> child.getParent()
                        .map(parent -> wanted.contains(ids.toDecompositionEdgeId(parent, child))).orElse(false))
                .toList();

        boolean deleted = false;
        for (Relation relation : relations) {
            deleted |= model.unrelate(relation.source(), relation.target()).isSuccess();
        }
        for (TreeNode<?> child : detached) {
            deleted |= child.setParent(null);
        }
        for (String elementId : elementIds) {
            if (model.findNode(elementId) != null) {
                model.deleteNode(elementId);
                deleted = true;
            }
        }
        return deleted;
    }

    @Override
    public boolean applyGModelBoundsChange(final String elementGModelId,
                                           final GPoint newPosition,
                                           final GDimension newSize,
                                           final IBigraphModelState state,
                                           final Injector injector) {
        if (newPosition == null) {
            return false;
        }

        POPPModel model = injector.getInstance(POPPExtensionContext.class).getOwnState().getPoppModel();
        TreeNode<?> node = model.findNode(elementGModelId);
        if (node == null) {
            return false;
        }

        node.move(newPosition.getX(), newPosition.getY());
        return true;
    }

    @Override
    public void writeExtensionMeta(BigraphMetaInformation meta, Injector injector) {
        POPPModel model = injector.getInstance(POPPExtensionContext.class).getOwnState().getPoppModel();
        Map<String, POPPExtensionMeta.NodeData> data = new HashMap<>();
        model.getAllNodes().forEach(node -> data.put(node.getId(), POPPExtensionMeta.NodeData.extractInfo(node)));
        meta.setExtensionSection(getId(), new POPPExtensionMeta(data).toJson());
    }

    /** Called before {@link #onModelLoaded}; the node data is applied by the loader once the model exists. */
    @Override
    public void readExtensionMeta(BigraphMetaInformation meta, Injector injector) {
        POPPExtensionState state = injector.getInstance(POPPExtensionContext.class).getOwnState();
        state.setInitialLoadedMeta(POPPExtensionMeta.fromJson(meta.getExtensionSection(getId())).nodes());
    }
}
