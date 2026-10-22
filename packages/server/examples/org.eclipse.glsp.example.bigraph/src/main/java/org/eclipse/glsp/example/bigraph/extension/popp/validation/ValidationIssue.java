package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import java.util.List;

/**
 * One finding of the {@link POPPModelValidator}.
 *
 * @param code     what is wrong, see {@link Code}
 * @param severity {@code ERROR} makes the model invalid, {@code WARNING} does not
 * @param message  human-readable, already specific to the matched nodes
 * @param nodeIds  ids of the POPP nodes involved (also their GModel ids), for highlighting; may be empty when the
 *                 offending bigraph node belongs to no domain node
 * @param elements names of the offending nodes in the bigraph (gate nodes, stubs, domain nodes)
 * @param pattern  name of the validation pattern whose match produced this issue
 */
public record ValidationIssue(Code code, Severity severity, String message, List<String> nodeIds,
                              List<String> elements, String pattern) {

    public enum Severity { ERROR, WARNING }

    public enum Code {
        /** A gate node directly in the POPP container instead of under a decomposed node. */
        GATE_OUTSIDE_DECOMPOSITION(Severity.ERROR),
        /** A relation stub directly in the POPP container or in a gate node instead of inside a domain node. */
        MISPLACED_STUB(Severity.ERROR),
        /** A relation stub whose edge has no partner stub. */
        DANGLING_STUB(Severity.ERROR),
        /** A domain node nested directly in another one, without a gate node in between. */
        CHILD_WITHOUT_DECOMPOSITION(Severity.ERROR),
        /** A node whose control is not part of the POPP signature. */
        UNKNOWN_CONTROL(Severity.ERROR),
        /** A gate node without any children (a decomposition type without decomposed children). */
        EMPTY_DECOMPOSITION(Severity.WARNING),
        /** More than one tree of the same kind, i.e. several root nodes of one kind in the POPP container. */
        MULTIPLE_TREES(Severity.WARNING);

        private final Severity severity;

        Code(Severity severity) { this.severity = severity; }

        public Severity severity() { return severity; }
    }
}
