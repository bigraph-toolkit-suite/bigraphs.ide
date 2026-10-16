package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.server.operations.Operation;

/** Sent by the client to switch the decomposition of a node with children between {@link DecompositionType} AND and OR. */
public class SwitchDecompositionTypeOperation extends Operation {
    public static final String KIND = "popp.switchDecompositionType";
    private String nodeId;
    private DecompositionType newType;

    public SwitchDecompositionTypeOperation() {
        super(KIND);
    }

    /**
     * @param nodeId    POPP node id (= GModel id)
     * @param newType   The new decomposition type of the given node
     */
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
