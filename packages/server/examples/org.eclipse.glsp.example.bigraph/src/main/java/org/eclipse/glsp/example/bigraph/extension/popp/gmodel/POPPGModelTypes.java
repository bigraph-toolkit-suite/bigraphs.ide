package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.Arrays;
import java.util.Optional;

/** Defines all glsp model types used in the frontend to visualize the domain model. */
public final class POPPGModelTypes {
    public static String of(NodeKind k)      { return "popp:" + k.name().toLowerCase(); }
    public static String of(RelationType t)  { return "popp:" + t.name().toLowerCase(); }

    /** The id of the POPP model variant; the variant's root is {@code VARIANT + "-root"}. */
    public static final String VARIANT             = "popp";
    public static final String NODE_DESCRIPTION    = "popp:node_description";
    public static final String DECOMPOSITION_EDGE  = "popp:decomposition_edge";
    public static final String DECOMPOSITION_PORT  = "popp:decomposition_port";
    public static final String CONNECT             = "popp:connect";

    public static Optional<NodeKind> nodeKindOf(String type) {
        return Arrays.stream(NodeKind.values()).filter(k -> of(k).equals(type)).findFirst();
    }
}
