package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.*;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

import java.util.Map;

/**
 * Per-session state of the POPP extension and its composition root: the model, its bigraph and GModel, and the
 * coverage analyzer. The analyzer is the fragment analyzer behind a cache; it reads model and bigraph lazily from
 * here, so it follows a model that is replaced when a file is (re)loaded.
 */
public class POPPExtensionState {
    private final IBigraphModelState bigraphModelState;
    private final POPPGModel gModel;
    private final CoverageAnalyzer analyzer;
    private POPPModel poppModel;
    private POPPBigraph poppBigraph;
    private Map<String, POPPExtensionMeta.NodeData> initialLoadedMeta; // Not updated, only relevant for model loading

    public POPPExtensionState(final IBigraphModelState bigraphModelState) {
        this.bigraphModelState = bigraphModelState;
        this.gModel = new POPPGModel(this);
        CoverageInput input = CoverageInput.lazy(this::getPoppModel, this::getPoppBigraph);
        this.analyzer = new CachingCoverageAnalyzer(input, new FragmentRewritingCoverageAnalyzer(input));
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

    public POPPBigraph getPoppBigraph() {
        return poppBigraph;
    }

    public void setPoppBigraph(POPPBigraph poppBigraph) {
        this.poppBigraph = poppBigraph;
    }

    public CoverageAnalyzer getAnalyzer() {
        return analyzer;
    }

    public POPPGModel getGModel() {
        return gModel;
    }

    public Map<String, POPPExtensionMeta.NodeData> getInitialLoadedMeta() {
        return initialLoadedMeta;
    }

    public void setInitialLoadedMeta(Map<String, POPPExtensionMeta.NodeData> initialLoadedMeta) {
        this.initialLoadedMeta = initialLoadedMeta;
    }
}