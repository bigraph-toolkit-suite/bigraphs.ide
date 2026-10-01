import {
    configureModelElement,
    GNode,
    GEdge,
    GLabel,
    GLabelView
} from '@eclipse-glsp/client';
import { ContainerModule } from 'inversify';
import { 
    POPPNodeView, 
    POPPAndDecompositionEdgeView, 
    POPPOrDecompositionEdgeView, 
    POPPRelationEdgeView 
} from './popp-views';

export const poppDiagramModule = new ContainerModule((bind, unbind, isBound, rebind) => {
    const context = { bind, unbind, isBound, rebind };

    configureModelElement(context, 'popp:problem', GNode, POPPNodeView);
    configureModelElement(context, 'popp:goal', GNode, POPPNodeView);
    configureModelElement(context, 'popp:consequence', GNode, POPPNodeView);
    configureModelElement(context, 'popp:solution', GNode, POPPNodeView);
    configureModelElement(context, 'popp:success_criteria', GNode, POPPNodeView);
    configureModelElement(context, 'popp:success_proof', GNode, POPPNodeView);

    configureModelElement(context, 'popp:node_description', GLabel, GLabelView);

    configureModelElement(context, 'popp:causes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:inverts', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:realizes', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:produces', GEdge, POPPRelationEdgeView);
    configureModelElement(context, 'popp:validates', GEdge, POPPRelationEdgeView);
    
    configureModelElement(context, 'popp:and', GEdge, POPPAndDecompositionEdgeView);
    configureModelElement(context, 'popp:or', GEdge, POPPOrDecompositionEdgeView);
});
