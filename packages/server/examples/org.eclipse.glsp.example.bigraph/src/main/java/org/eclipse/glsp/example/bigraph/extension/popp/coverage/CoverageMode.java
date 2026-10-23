package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What coverage is computed for: the node kinds that count as covered by themselves (the <i>emitters</i>, from which
 * coverage propagates through decompositions and relations), and the node kinds the question is about (the
 * <i>targets</i>, which the gaps are reported for). The coverage definition is the same for every mode; only the set
 * of intrinsically covered kinds differs.
 * <p>
 * The {@link #presets() presets} are the questions the extension answers. {@link #PLANNING} and {@link #VERIFY} cover
 * the whole model, the other three answer one question of the task each and are also shown in the report
 * (see {@link CoverageReportBuilder}). The client lists the same presets by {@link #name()}.
 *
 * @param name     stable identifier, used in actions
 * @param label    short display name
 * @param emitters the intrinsically covered kinds
 * @param targets  the kinds the question is about
 */
public record CoverageMode(String name, String label, Set<NodeKind> emitters, Set<NodeKind> targets) {

    /** Every success criterion counts as covered by itself: "is everything accounted for in the plan?" */
    public static final CoverageMode PLANNING = new CoverageMode("PLANNING", "Planning",
            EnumSet.of(NodeKind.SOLUTION, NodeKind.SUCCESS_CRITERIA), Coverage.COVERABLE_KINDS);
    /**
     * A success criterion counts only once a proof produced by a solution validates it: "has the project actually been
     * shown to solve the problems?"
     */
    public static final CoverageMode VERIFY = new CoverageMode("VERIFY", "Verify",
            EnumSet.of(NodeKind.SOLUTION), Coverage.COVERABLE_KINDS);
    /** Is the goal tree covered by the success criteria tree? (On goals and problems the same as {@link #PLANNING}.) */
    public static final CoverageMode GOALS_BY_CRITERIA = new CoverageMode("GOALS_BY_CRITERIA", "Goals by criteria",
            EnumSet.of(NodeKind.SUCCESS_CRITERIA), EnumSet.of(NodeKind.GOAL));
    /** Is the problem tree covered by the goal tree? */
    public static final CoverageMode PROBLEMS_BY_GOALS = new CoverageMode("PROBLEMS_BY_GOALS", "Problems by goals",
            EnumSet.of(NodeKind.GOAL), EnumSet.of(NodeKind.PROBLEM));
    /**
     * Is the problem tree covered by the success proof tree? Every proof counts, whether or not a solution produces it
     * ({@link #VERIFY} is the stricter variant). Implies coverage by goals, since coverage only reaches problems
     * through goals.
     */
    public static final CoverageMode PROBLEMS_BY_PROOFS = new CoverageMode("PROBLEMS_BY_PROOFS", "Problems by proofs",
            EnumSet.of(NodeKind.SUCCESS_PROOF), EnumSet.of(NodeKind.PROBLEM));

    /** The questions of the task first, then the two modes about the whole model. */
    private static final List<CoverageMode> PRESETS =
            List.of(PROBLEMS_BY_GOALS, PROBLEMS_BY_PROOFS, GOALS_BY_CRITERIA, PLANNING, VERIFY);

    public CoverageMode {
        Objects.requireNonNull(name);
        Objects.requireNonNull(label);
        emitters = Collections.unmodifiableSet(emitters.isEmpty() ? EnumSet.noneOf(NodeKind.class) : EnumSet.copyOf(emitters));
        targets = Collections.unmodifiableSet(targets.isEmpty() ? EnumSet.noneOf(NodeKind.class) : EnumSet.copyOf(targets));
    }

    /** The modes offered to the user, in display order. */
    public static List<CoverageMode> presets() {
        return PRESETS;
    }

    public static Optional<CoverageMode> fromString(String name) {
        return PRESETS.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** The preset called {@code name} (as {@code Enum.valueOf}, e.g. for test parameters). */
    public static CoverageMode valueOf(String name) {
        return fromString(name).orElseThrow(() -> new IllegalArgumentException("No coverage mode " + name));
    }

    /** Whether a node of {@code kind} is covered in this mode by itself, without children or relations. */
    public boolean isIntrinsic(NodeKind kind) {
        return emitters.contains(kind);
    }

    /** Whether nodes of {@code kind} are what this mode asks about. */
    public boolean isTarget(NodeKind kind) {
        return targets.contains(kind);
    }

    /**
     * The kinds gaps are reported for: those that coverage can flow through on its way from an emitter to a target
     * (the targets included, the emitters excluded). For "problems by proofs" these are success criteria, goals and
     * problems, so the gaps show where the chain breaks; kinds no emitter can reach (e.g. success criteria when only
     * goals are emitters) are left out, since they could only ever be reported as uncovered.
     */
    public Set<NodeKind> gapKinds() {
        Map<NodeKind, Set<NodeKind>> flow = flow(false), back = flow(true);
        Set<NodeKind> downstream = reach(emitters, flow);   // at least one step from an emitter
        Set<NodeKind> upstream = reach(targets, back);
        upstream.addAll(targets);
        Set<NodeKind> out = EnumSet.noneOf(NodeKind.class);
        for (NodeKind k : downstream) if (upstream.contains(k) && !emitters.contains(k)) out.add(k);
        return Collections.unmodifiableSet(out);
    }

    /**
     * The kinds whose nodes can be raised in this mode: every kind coverage can flow to over propagating relations,
     * starting from the intrinsically covered kinds, except those kinds themselves (they start covered). Nodes of every
     * other kind stay uncovered in this mode, so no rule can raise them.
     */
    public Set<NodeKind> raisableKinds() {
        Set<NodeKind> out = reach(emitters, flow(false));
        out.removeAll(emitters);
        return Collections.unmodifiableSet(out);
    }

    /** Coverage flow between kinds over the propagating relation types ({@code backwards}: against the flow). */
    private static Map<NodeKind, Set<NodeKind>> flow(boolean backwards) {
        Map<NodeKind, Set<NodeKind>> out = new EnumMap<>(NodeKind.class);
        for (NodeKind k : NodeKind.values()) out.put(k, EnumSet.noneOf(NodeKind.class));
        for (RelationType type : RelationType.values()) {
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                NodeKind giver, receiver;
                switch (type.getCoveragePropagation()) {
                    case FORWARD -> { giver = pair.from(); receiver = pair.to(); }
                    case BACKWARD -> { giver = pair.to(); receiver = pair.from(); }
                    default -> { continue; }
                }
                if (backwards) out.get(receiver).add(giver); else out.get(giver).add(receiver);
            }
        }
        return out;
    }

    private static Set<NodeKind> reach(Set<NodeKind> from, Map<NodeKind, Set<NodeKind>> edges) {
        Set<NodeKind> seen = EnumSet.noneOf(NodeKind.class);
        Deque<NodeKind> todo = new ArrayDeque<>(from);
        while (!todo.isEmpty()) {
            for (NodeKind next : edges.get(todo.pop())) if (seen.add(next)) todo.push(next);
        }
        return seen;
    }

    @Override
    public String toString() {
        return name;
    }
}
