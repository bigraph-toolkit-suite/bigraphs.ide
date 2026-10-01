package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.server.operations.Operation;

public class SwitchDecompositionTypeOperation extends Operation {
    public static final String KIND = "popp.switchDecompositionType";
    private String nodeId;
    private DecompositionType newType;

    public SwitchDecompositionTypeOperation() {
        super(KIND);
    }

    public SwitchDecompositionTypeOperation(final String nodeId, final DecompositionType newType) {
        super(KIND);
        this.nodeId = nodeId;
        this.newType = newType;
    }

    public DecompositionType getNewType() {
        return newType;
    }

    public void setNewType(final DecompositionType newType) {
        this.newType = newType;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(final String nodeId) {
        this.nodeId = nodeId;
    }
}
