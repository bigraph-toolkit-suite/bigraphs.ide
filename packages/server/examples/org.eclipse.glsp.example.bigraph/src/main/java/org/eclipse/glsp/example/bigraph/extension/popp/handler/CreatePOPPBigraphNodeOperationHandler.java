package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.eclipse.glsp.example.bigraph.handler.CreateBigraphNodeOperationHandler;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

public class CreatePOPPBigraphNodeOperationHandler extends CreateBigraphNodeOperationHandler {

    @Override
    public void executeCreation(final CreateNodeOperation operation) {
        Map<String, String> args = operation.getArgs();
        String decompositionType = args.get("decompositionType");
        if (decompositionType == null || decompositionType.isBlank() || "NONE".equals(decompositionType)) {
            super.executeCreation(operation);
            return;
        }

        Set<NodeEntity<?>> existingNodes = new HashSet<>(modelState.getMutableBigraph().getNodes());
        super.executeCreation(operation);
        modelState.getMutableBigraph().getNodes().stream()
                .filter(node -> !existingNodes.contains(node))
                .findFirst()
                .ifPresent(node -> node.getAttributes().put("decompositionType", decompositionType));
    }
}