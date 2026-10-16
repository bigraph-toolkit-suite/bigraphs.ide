package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.Arrays;
import java.util.Optional;

/** Which success criteria count as covered: one mode per stage of the project. */
public enum CoverageMode {
    /** Every success criterion counts as covered by itself: "is everything accounted for in the plan?" */
    PLANNING,
    /**
     * A success criterion counts only once a proof produced by a solution validates it: "has the project actually been
     * shown to solve the problems?"
     */
    VERIFY;

    public static Optional<CoverageMode> fromString(String name) {
        return Arrays.stream(values()).filter(m -> m.name().equals(name)).findFirst();
    }
}

