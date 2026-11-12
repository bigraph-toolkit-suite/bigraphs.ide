package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature.Control;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/** The signature of the {@link CoverageBigraph}: the {@link POPPBigraphSignature} and the {@link CoverageMarker}s. */
final class CoverageSignature {
    private static final class Holder { static final DynamicSignature INSTANCE = create(); }

    static DynamicSignature get() { return Holder.INSTANCE; }

    private static DynamicSignature create() {
        var builder = pureSignatureBuilder();
        for (Control c : POPPBigraphSignature.controls()) {
            builder.newControl().identifier(c.controlName()).arity(FiniteOrdinal.ofInteger(c.arity())).assign();
        }
        for (CoverageMarker m : CoverageMarker.values()) {
            builder.newControl().identifier(m.controlName()).arity(FiniteOrdinal.ofInteger(m.arity())).assign();
        }
        return builder.create();
    }
}
