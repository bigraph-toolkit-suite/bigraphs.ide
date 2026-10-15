package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.Action;

/**
 * Tells the client to open the inline editor of a node's description label, e.g. right after the node was created,
 * so the user can type the description without the double click. Sent after the model update that contains the node.
 */
public class StartDescriptionEditAction extends Action {
    public static final String KIND = "popp.startDescriptionEdit";
    private String labelId;

    public StartDescriptionEditAction() {
        super(KIND);
    }

    public StartDescriptionEditAction(final String labelId) {
        super(KIND);
        this.labelId = labelId;
    }

    public String getLabelId() { return labelId; }
    public void setLabelId(final String labelId) { this.labelId = labelId; }
}
