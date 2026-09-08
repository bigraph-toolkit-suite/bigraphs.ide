package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

public class POPPExtensionState {
    private final IBigraphModelState bigraphModelState;
    private final POPPModel poppModel;

    //TODO

    public POPPExtensionState(final IBigraphModelState bigraphModelState) {
        this.bigraphModelState = bigraphModelState;
        this.poppModel = null;
    }
}