import {
    configureActionHandler,
    configureModelElement,
    EnableDefaultToolsAction,
    editLabelFeature,
    GEdge,
    GLabel,
    GNode,
    GPort,
    SetModelAction,
    TYPES,
    UpdateModelAction
} from '@eclipse-glsp/client';
import { ContainerModule } from 'inversify';
import { alignFeature, connectableFeature } from 'sprotty';
import { InspectionController } from './inspection-controller';
import { JsonExportAction, SetInspectionAction, StartDescriptionEditAction, ValidationResultAction } from './popp-actions';
import { PoppJsonTransfer } from './popp-json-transfer';
import { DescriptionEditStarter } from './description-edit-starter';
import { PoppAnalysisController } from './popp-analysis-controller';
import { PoppToolLifecycle } from './palette/popp-tool-lifecycle';
import {
    POPPDecompositionEdgeView,
    POPPDecompositionPortView,
    POPPDescriptionLabelView,
    POPPNodeView,
    POPPRelationEdgeView
} from './popp-views';

import './popp-diagram.css';
import './popp-kind-colors.css';
import './popp-inspection.css';

export const poppDiagramModule = new ContainerModule((bind, unbind, isBound, rebind) => {
    const context = { bind, unbind, isBound, rebind };

    configureModelElement(context, 'popp:problem', GNode, POPPNodeView);
    configureModelElement(context, 'popp:goal', GNode, POPPNodeView);
    configureModelElement(context, 'popp:consequence', GNode, POPPNodeView);
    configureModelElement(context, 'popp:solution', GNode, POPPNodeView);
    configureModelElement(context, 'popp:success_criteria', GNode, POPPNodeView);
    configureModelElement(context, 'popp:success_proof', GNode, POPPNodeView);

    configureModelElement(context, 'popp:node_description', GLabel, POPPDescriptionLabelView, {
        enable: [editLabelFeature],
        disable: [alignFeature]
    });
    configureModelElement(context, 'popp:decomposition_port', GPort, POPPDecompositionPortView, { disable: [connectableFeature] });
    configureModelElement(context, 'popp:decomposition_edge', GEdge, POPPDecompositionEdgeView);

    configureModelElement(context, 'popp:causes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:inverts', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:realizes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:produces', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:validates', GEdge, POPPRelationEdgeView);

    // Click-to-inspect. Bound as a singleton first so the mouse listener and the action handlers share one instance.
    bind(InspectionController).toSelf().inSingletonScope();
    bind(TYPES.MouseListener).toService(InspectionController);
    configureActionHandler(context, SetInspectionAction.KIND, InspectionController);
    configureActionHandler(context, UpdateModelAction.KIND, InspectionController);
    configureActionHandler(context, SetModelAction.KIND, InspectionController);

    // Validation results, coverage report/gaps, "highlight uncovered" and the sidebar relay
    bind(PoppAnalysisController).toSelf().inSingletonScope();
    configureActionHandler(context, ValidationResultAction.KIND, PoppAnalysisController);
    configureActionHandler(context, UpdateModelAction.KIND, PoppAnalysisController);
    configureActionHandler(context, SetModelAction.KIND, PoppAnalysisController);

    // Description editor right after creating a node (requested by the server)
    bind(DescriptionEditStarter).toSelf().inSingletonScope();
    configureActionHandler(context, StartDescriptionEditAction.KIND, DescriptionEditStarter);

    // JSON export (server -> extension's save dialog) and import (extension's open dialog -> server)
    bind(PoppJsonTransfer).toSelf().inSingletonScope();
    configureActionHandler(context, JsonExportAction.KIND, PoppJsonTransfer);

    // Lets the palette follow creation-tool lifecycle (highlight reset, connect re-arm)
    bind(PoppToolLifecycle).toSelf().inSingletonScope();
    configureActionHandler(context, EnableDefaultToolsAction.KIND, PoppToolLifecycle);
});