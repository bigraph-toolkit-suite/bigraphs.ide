import {
    configureModelElement,
    GNode,
    GEdge,
    GLabel,
    GPort,
    editLabelFeature,
} from '@eclipse-glsp/client';
import { ContainerModule } from 'inversify';
import { 
    POPPNodeView, 
    POPPDecompositionEdgeView, 
    POPPDecompositionPortView,
    POPPRelationEdgeView,
    POPPDescriptionLabelView
} from './popp-views';
import { alignFeature, connectableFeature } from 'sprotty';

import './popp-diagram.css'


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
    configureModelElement(context, 'popp:decomposition_port', GPort, POPPDecompositionPortView, {disable: [connectableFeature]});
    configureModelElement(context, 'popp:decomposition_edge', GEdge, POPPDecompositionEdgeView);

    configureModelElement(context, 'popp:causes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:inverts', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:realizes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:produces', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:validates', GEdge, POPPRelationEdgeView); 
});
