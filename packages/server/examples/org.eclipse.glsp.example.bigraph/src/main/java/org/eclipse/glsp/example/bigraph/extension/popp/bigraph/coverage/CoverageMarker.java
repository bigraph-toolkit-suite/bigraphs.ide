package org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.Coverage;

import java.util.Arrays;
import java.util.Optional;

public enum CoverageMarker {
    // coverage state markers
    UNC("Unc", 0),
    PART("Part", 0),
    COV("Cov", 0),

    // partial coverage reason markers
    PART_DECOMP("PartDecomp", 0),
    PART_LINK("PartLink", 1),

    // coverage reason markers
    COV_INTRINSIC("CovIntrinsic", 0),
    COV_DECOMP("CovDecomp", 0),
    COV_LINK("CovLink", 1);

    private final String controlName;
    private final int arity;

    CoverageMarker(String controlName, int arity) { this.controlName = controlName; this.arity = arity; }

    public String controlName() { return controlName; }
    public int arity() { return arity; }

    public static CoverageMarker of(Coverage c) {
        return switch (c) { case UNCOVERED -> UNC; case PARTIAL -> PART; case COVERED -> COV; };
    }

    public Optional<Coverage> state() {
        return switch (this) {
            case UNC -> Optional.of(Coverage.UNCOVERED);
            case PART -> Optional.of(Coverage.PARTIAL);
            case COV -> Optional.of(Coverage.COVERED);
            default -> Optional.empty();
        };
    }

    public boolean belongsTo(Coverage s) {
        return switch (this) {
            case PART_DECOMP, PART_LINK -> s == Coverage.PARTIAL;
            case COV_INTRINSIC, COV_DECOMP, COV_LINK -> s == Coverage.COVERED;
            default -> false;
        };
    }

    public static Optional<CoverageMarker> forNode(BigraphEntity.NodeEntity<?> node) {
        String name = node.getControl().getNamedType().stringValue();
        return Arrays.stream(values()).filter(m -> m.controlName.equals(name)).findFirst();
    }
}