package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.server.operations.Operation;

/**
 * Sent by the client to switch the decomposition of the node {@code nodeId} (POPP node id = GModel id), which has
 * children, to {@code newType}, {@link DecompositionType#AND} or {@link DecompositionType#OR}.
 */
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

    public String getNodeId() { return nodeId; }
    public DecompositionType getNewType() { return newType; }
}
