package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Arrays;
import java.util.Optional;

/** Discriminates the six POPP tree kinds, e.g. for event payloads and error messages. */
public enum NodeKind {
    PROBLEM(false),
    GOAL(false),
    CONSEQUENCE(false),
    SOLUTION(false),
    SUCCESS_CRITERIA(false),
    SUCCESS_PROOF(true);

    private NodeKind(boolean explicitlyCovered) {
        this.explicitlyCovered = explicitlyCovered;
    }

    private final boolean explicitlyCovered;

    public boolean isExplicitlyCovered() {
        return explicitlyCovered;
    }

    public static Optional<NodeKind> fromString(String name){
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }
}
