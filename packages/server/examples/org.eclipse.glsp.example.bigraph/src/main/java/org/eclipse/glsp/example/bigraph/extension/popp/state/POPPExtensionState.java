package org.eclipse.glsp.example.bigraph.extension.popp.state;

import org.eclipse.glsp.example.bigraph.extension.popp.io.POPPExtensionMeta;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.*;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModel;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelTypes;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

import java.util.Map;

/**
 * Per-session state of the POPP extension and its composition root: the model, its bigraph and GModel, and the
 * coverage analyzer. The analyzer is the {@link IncrementalCoverageAnalyzer} (one coverage bigraph per mode, edited in
 * place and rewritten with worklist, prefilter and seed); it observes the current bigraph and reads model and bigraph
 * lazily from here, so it follows a model that is replaced when a file is (re)loaded.
 */
public class POPPExtensionState {
    private final IBigraphModelState bigraphModelState;
    private final POPPGModel gModel;
    private final IncrementalCoverageAnalyzer analyzer;
    private POPPModel poppModel;
    private POPPBigraph poppBigraph;
    private Map<String, POPPExtensionMeta.NodeData> initialLoadedMeta; // Not updated, only relevant for model loading

    public POPPExtensionState(final IBigraphModelState bigraphModelState) {
        this.bigraphModelState = bigraphModelState;
        this.gModel = new POPPGModel(() -> bigraphModelState instanceof BigraphModelState s
                ? s.getVariantRoot(POPPGModelTypes.VARIANT) : null);
        CoverageInput input = CoverageInput.lazy(this::getPoppModel, this::getPoppBigraph);
        this.analyzer = new IncrementalCoverageAnalyzer(input);
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
        if (this.poppBigraph != null) this.poppBigraph.removeListener(analyzer);
        this.poppBigraph = poppBigraph;
        if (poppBigraph != null) poppBigraph.addListener(analyzer);
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