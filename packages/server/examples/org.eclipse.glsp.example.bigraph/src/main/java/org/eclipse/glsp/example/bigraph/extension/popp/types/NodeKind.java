package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Discriminates the six POPP tree kinds, e.g. for event payloads and error messages. */
public enum NodeKind {
    PROBLEM, GOAL, CONSEQUENCE, SOLUTION, SUCCESS_CRITERIA, SUCCESS_PROOF
}
