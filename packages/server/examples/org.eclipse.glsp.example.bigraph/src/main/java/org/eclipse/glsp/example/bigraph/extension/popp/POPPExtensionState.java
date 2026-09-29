package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

public class POPPExtensionState {
    private final IBigraphModelState bigraphModelState;
    private final POPPGModel gModel;
    private POPPModel poppModel;

    public POPPExtensionState(final IBigraphModelState bigraphModelState) {
        this.bigraphModelState = bigraphModelState;
        this.gModel = new POPPGModel(this);
    }

    public IBigraphModelState getBigraphModelState() {
        return bigraphModelState;
    }

    public void setPoppModel(POPPModel poppModel) {
        this.poppModel = poppModel;
    }

    public POPPModel getPoppModel() {
        return poppModel;
    }


    public POPPGModel getGModel() {
        return gModel;
    }
}