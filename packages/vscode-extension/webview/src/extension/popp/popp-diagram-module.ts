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
import { SetInspectionAction } from './popp-actions';
import { PoppToolLifecycle } from './palette/popp-tool-lifecycle';
import {
    POPPDecompositionEdgeView,
    POPPDecompositionPortView,
    POPPDescriptionLabelView,
    POPPNodeView,
    POPPRelationEdgeView
} from './popp-views';

import './popp-diagram.css';
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

    // Lets the palette follow creation-tool lifecycle (highlight reset, connect re-arm)
    bind(PoppToolLifecycle).toSelf().inSingletonScope();
    configureActionHandler(context, EnableDefaultToolsAction.KIND, PoppToolLifecycle);
});