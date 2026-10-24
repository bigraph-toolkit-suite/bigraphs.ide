package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;

import java.util.function.Supplier;

/**
 * Supplies the model (and its bigraph) an analyzer evaluates. Read on every {@code session} call, so one analyzer
 * follows a model that is replaced, e.g. when a file is reloaded.
 */
public interface CoverageInput {
    /** The current model, or {@code null} if none is loaded. */
    POPPModel model();

    /** The model's bigraph, kept in sync by the bigraph synchronizer; only used by the rewriting analyzers. */
    POPPBigraph bigraph();

    /**
     * Identifies the model state results depend on: equal revisions mean equal results. The default is the model's
     * identity plus its version, so a reloaded model never reuses results of the old one; {@code null} (no model)
     * disables caching.
     */
    default Object revision() {
        POPPModel m = model();
        return m == null ? null : new Revision(m, m.getVersion());
    }

    record Revision(POPPModel model, long version) {}

    /** Reads model and bigraph from the suppliers on every access, e.g. from the extension state. */
    static CoverageInput lazy(Supplier<POPPModel> model, Supplier<POPPBigraph> bigraph) {
        return new CoverageInput() {
            public POPPModel model() { return model.get(); }
            public POPPBigraph bigraph() { return bigraph.get(); }
        };
    }

    /** A fixed model and bigraph, e.g. in tests. */
    static CoverageInput of(POPPModel model, POPPBigraph bigraph) {
        return lazy(() -> model, () -> bigraph);
    }
}