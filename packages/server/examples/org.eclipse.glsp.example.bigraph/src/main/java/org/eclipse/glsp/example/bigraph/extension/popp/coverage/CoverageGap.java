package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

/** One way a node could still become covered: a missing neighbour of {@code missingKind} connected via {@code type}. */
public record CoverageGap(RelationType type, NodeKind missingKind, boolean missingIsSource) {
    public String describe() {
        return "needs a " + missingKind.getName() + " (" + type.name().toLowerCase() + ")";
    }
}

