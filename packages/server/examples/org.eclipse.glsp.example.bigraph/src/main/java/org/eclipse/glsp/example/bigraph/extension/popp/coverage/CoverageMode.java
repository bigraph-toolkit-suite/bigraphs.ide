package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.Arrays;
import java.util.Optional;

public enum CoverageMode {
    /** Any SuccessProof node counts, whatever its status: "is everything accounted for in the plan?" */
    PLANNED,
    /** Only ACHIEVED proofs count: "has the project actually been shown to work?" */
    VERIFIED;

    public static Optional<CoverageMode> fromString(String name) {
        return Arrays.stream(values()).filter(m -> m.name().equals(name)).findFirst();
    }
}

