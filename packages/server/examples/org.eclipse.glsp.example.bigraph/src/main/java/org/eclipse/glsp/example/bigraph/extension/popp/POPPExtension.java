package org.eclipse.glsp.example.bigraph.extension.popp;

import com.google.inject.Binder;
import com.google.inject.Singleton;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPEdgeCreationChecker;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPTypeHints;
import org.eclipse.glsp.example.bigraph.extension.popp.handler.CreatePOPPBigraphNodeOperationHandler;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateKey;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.model.ModelVariant;
import org.eclipse.glsp.server.actions.ActionHandler;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;
import org.eclipse.glsp.server.operations.OperationHandler;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
        return List.of(); //TODO
    }

    @Override
    public List<Class<? extends OperationHandler<?>>> getOperationHandlers() {
        return List.of(CreatePOPPBigraphNodeOperationHandler.class);
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
}
