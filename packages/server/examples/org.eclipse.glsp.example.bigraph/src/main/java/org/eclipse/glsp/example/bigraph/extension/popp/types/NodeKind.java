package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Arrays;
import java.util.Optional;

/** Discriminates the six POPP tree kinds, e.g. for event payloads and error messages. */
public enum NodeKind {
    PROBLEM(false, false),
    GOAL(false, false),
    CONSEQUENCE(false, false),
    SOLUTION(true, false),
    SUCCESS_CRITERIA(false, true),
    SUCCESS_PROOF(false, false);

    private NodeKind(boolean explicitlyCovered, boolean coveredWhenPlanned) {
        this.explicitlyCovered = explicitlyCovered;
        this.coveredWhenPlanned = coveredWhenPlanned;
    }

    private final boolean explicitlyCovered;
    private final boolean coveredWhenPlanned;

    public boolean isIntrinsicallyCovered() {
        return explicitlyCovered;
    }

    public boolean isCoveredWhenPlanned() {
        return coveredWhenPlanned;
    }

    public static Optional<NodeKind> fromString(String name){
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }

    public String getName(){
        return name().toLowerCase().replace("_", " ");
    }
}
