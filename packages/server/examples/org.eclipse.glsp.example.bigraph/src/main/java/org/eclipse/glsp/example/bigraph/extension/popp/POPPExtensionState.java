package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

public class POPPExtensionState {
    private final IBigraphModelState bigraphModelState;
    private final POPPModel poppModel;

    public POPPExtensionState(final IBigraphModelState bigraphModelState, final POPPModel poppModel) {
        this.bigraphModelState = bigraphModelState;
        this.poppModel = poppModel;
    }

    public IBigraphModelState getBigraphModelState() {
        return bigraphModelState;
    }

    public POPPModel getPoppModel() {
        return poppModel;
    }
}