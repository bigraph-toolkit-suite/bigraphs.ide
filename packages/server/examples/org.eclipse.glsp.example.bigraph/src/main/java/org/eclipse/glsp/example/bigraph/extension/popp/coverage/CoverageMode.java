package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.Arrays;
import java.util.Optional;

public enum CoverageMode {
    /** Any SuccessCriteria node counts, whatever its status: "is everything accounted for in the plan?" */
    PLANNING,
    /** Only ACHIEVED proofs (produces by solutions) count: "has the project actually been shown to work?" */
    VERIFY;

    public static Optional<CoverageMode> fromString(String name) {
        return Arrays.stream(values()).filter(m -> m.name().equals(name)).findFirst();
    }
}

