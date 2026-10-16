package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;

import java.util.Arrays;
import java.util.Optional;

/**
 * The six POPP node kinds. Each kind also states its intrinsic coverage: a solution is always covered, a success
 * criterion only in {@link CoverageMode#PLANNING} (in VERIFY it needs a validating proof).
 */
public enum NodeKind {
    PROBLEM(false, false),
    GOAL(false, false),
    CONSEQUENCE(false, false),
    SOLUTION(true, false),
    SUCCESS_CRITERIA(false, true),
    SUCCESS_PROOF(false, false);

    NodeKind(boolean alwaysCovered, boolean coveredWhenPlanning) {
        this.alwaysCovered = alwaysCovered;
        this.coveredWhenPlanning = coveredWhenPlanning;
    }

    private final boolean alwaysCovered;
    private final boolean coveredWhenPlanning;

    /** Whether a node of this kind is covered in {@code mode} by itself, without children or relations. */
    public boolean isIntrinsicallyCovered(CoverageMode mode) {
        return alwaysCovered || (mode == CoverageMode.PLANNING && coveredWhenPlanning);
    }

    public static Optional<NodeKind> fromString(String name){
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }

    /** Lower-case display name, e.g. "success criteria". */
    public String getName(){
        return name().toLowerCase().replace("_", " ");
    }
}
