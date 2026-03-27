package org.eclipse.glsp.example.bigraph.actions;

import org.eclipse.glsp.server.actions.Action;

public class ConvertNameRoleAction extends Action {
    public static final String KIND = "bigraphConvertNameRole";

    private String elementId;
    private String targetRole;

    public ConvertNameRoleAction() {
        super(KIND);
    }

    public ConvertNameRoleAction(final String elementId, final String targetRole) {
        super(KIND);
        this.elementId = elementId;
        this.targetRole = targetRole;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(final String elementId) {
        this.elementId = elementId;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(final String targetRole) {
        this.targetRole = targetRole;
    }
}
