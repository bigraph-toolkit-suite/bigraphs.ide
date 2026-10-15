package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;

import java.util.function.Supplier;

/** Used to initiate CoverageAnalyzers */
public interface CoverageInput {
    POPPModel model();
    /** Only used by rewriting-based Analyzers */
    POPPBigraph bigraph();

    default Object revision() {
        POPPModel m = model();
        return m == null ? null : new Revision(m, m.getVersion());
    }

    record Revision(POPPModel model, long version) {}

    static CoverageInput lazy(Supplier<POPPModel> model, Supplier<POPPBigraph> bigraph) {
        return new CoverageInput() {
            public POPPModel model() { return model.get(); }
            public POPPBigraph bigraph() { return bigraph.get(); }
        };
    }

    static CoverageInput of(POPPModel model, POPPBigraph bigraph) {
        return lazy(() -> model, () -> bigraph);
    }
}