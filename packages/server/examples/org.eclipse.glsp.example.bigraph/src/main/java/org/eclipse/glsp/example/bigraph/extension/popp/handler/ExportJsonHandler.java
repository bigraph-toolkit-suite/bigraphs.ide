package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelTypes;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.JsonExportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestJsonExportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.io.POPPJson;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;

import java.io.File;
import java.util.List;

/** Answers a {@link RequestJsonExportAction} with the current model as a POPP JSON document. Read-only. */
public class ExportJsonHandler extends AbstractActionHandler<RequestJsonExportAction> {
    @Inject
    protected POPPExtensionContext context;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Inject
    protected VariantGate variantGate;

    @Override
    protected List<Action> executeAction(final RequestJsonExportAction action) {
        POPPModel model = context.getOwnState().getPoppModel();
        if (!variantGate.isActive(POPPGModelTypes.VARIANT) || model == null) {
            BigraphNotifications.notifyError(actionDispatcher, "POPP JSON export needs an open POPP model.");
            return List.of();
        }
        return List.of(new JsonExportAction(POPPJson.write(model), fileName()));
    }

    /** {@code <name of the open file without .xmi>.popp.json}. */
    private String fileName() {
        String path = context.getBigraphModelState() instanceof BigraphModelState s ? s.getSourceFilePath() : null;
        String base = path == null ? "popp-model" : new File(path).getName().replaceFirst("\\.xmi$", "");
        return base + ".popp.json";
    }
}
