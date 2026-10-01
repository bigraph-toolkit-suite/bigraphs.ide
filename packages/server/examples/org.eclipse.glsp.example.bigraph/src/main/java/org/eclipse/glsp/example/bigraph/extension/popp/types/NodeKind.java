package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Arrays;
import java.util.Optional;

/** Discriminates the six POPP tree kinds, e.g. for event payloads and error messages. */
public enum NodeKind {
    PROBLEM, GOAL, CONSEQUENCE, SOLUTION, SUCCESS_CRITERIA, SUCCESS_PROOF;

    public static Optional<NodeKind> fromString(String name){
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }
}
