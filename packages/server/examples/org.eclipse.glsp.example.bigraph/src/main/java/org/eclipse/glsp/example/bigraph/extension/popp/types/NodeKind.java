package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Arrays;
import java.util.Optional;

/**
 * The six POPP node kinds. Which kinds count as covered by themselves depends on the coverage mode
 * ({@code CoverageMode#emitters()}), not on the kind.
 */
public enum NodeKind {
    PROBLEM,
    GOAL,
    CONSEQUENCE,
    SOLUTION,
    SUCCESS_CRITERIA,
    SUCCESS_PROOF;

    public static Optional<NodeKind> fromString(String name){
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }

    /** Lower-case display name, e.g. "success criteria". */
    public String getName(){
        return name().toLowerCase().replace("_", " ");
    }
}
