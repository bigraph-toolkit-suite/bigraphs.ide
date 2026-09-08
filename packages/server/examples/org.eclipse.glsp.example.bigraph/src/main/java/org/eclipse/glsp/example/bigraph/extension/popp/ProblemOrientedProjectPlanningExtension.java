package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.handler.CreatePOPPBigraphNodeOperationHandler;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateKey;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.model.ModelVariant;
import org.eclipse.glsp.server.actions.ActionHandler;
import org.eclipse.glsp.server.operations.OperationHandler;

import java.util.List;

public class ProblemOrientedProjectPlanningExtension implements IdeExtension {
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
}
