package org.eclipse.glsp.example.bigraph.extension.popp.coverage;


/** Ordered UNCOVERED &lt; PARTIAL &lt; COVERED, so {@link #best} is just a max. */
public enum Coverage {
    UNCOVERED,
    PARTIAL,
    COVERED;

    public static Coverage best(Coverage a, Coverage b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
