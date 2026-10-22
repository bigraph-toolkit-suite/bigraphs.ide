package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * Reference point for one schedule of {@link ScheduledRewritingCoverageAnalyzer}, not an analyzer: the cost of its
 * matcher calls that <i>hit</i> and their rewrites, as if a perfect pruning oracle skipped every call of that schedule
 * that finds no match.
 * <p>
 * The oracle is a recording: {@link #prepare} runs the schedule once, traced, and stores, per round, the sequence of
 * rule calls that found a match (guard calls are not rule calls and are dropped). {@link #session} replays it on a
 * freshly built coverage bigraph: every call hits and is followed by its rewrite, with the same matcher API as the
 * schedule ({@code matchFirst} for the {@code *-first} schedules, otherwise the first of all matches), so the replay
 * rewrites in the same order with the same matches. There are no guards, no prefilter, no worklist bookkeeping and no
 * failed calls: the time is hits x (cost of a hitting call + its rewrite), plus building the coverage bigraph and the
 * readout, i.e. a lower bound for that schedule. With rewriting the order matters (what a call costs depends on the
 * agent it runs on), so every schedule has its own oracle; the replay is checked against the recording's result once.
 * <p>
 * Valid for the model it was prepared on: a session on a changed model prepares again (that session then also pays the
 * recording). Benchmark it with {@code inspectRoot} after {@link #prepare}; a cold-start number means nothing here,
 * because preparing already warms the JVM.
 */
public final class OracleRewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();

    /** Per round, the indices (into {@code RULES}) of the hitting calls, in call order. */
    private record Schedule(String fingerprint, List<int[]> rounds, Map<String, Coverage> expected) {}

    private final CoverageInput input;
    private final ScheduledRewritingCoverageAnalyzer.Options recorded;
    private final Map<CoverageMode, Schedule> schedules = new HashMap<>();
    private volatile RewritingStats lastStats;

    /** The oracle of the default schedule. */
    public OracleRewritingCoverageAnalyzer(CoverageInput input) {
        this(input, ScheduledRewritingCoverageAnalyzer.Options.DEFAULT);
    }

    /** The oracle of the schedule {@code recorded}. */
    public OracleRewritingCoverageAnalyzer(CoverageInput input, ScheduledRewritingCoverageAnalyzer.Options recorded) {
        this.input = input;
        this.recorded = recorded;
    }

    public RewritingStats lastStats() { return lastStats; }

    private volatile boolean tracing;
    private volatile RewritingTrace lastTrace;

    /** Record a {@link RewritingTrace} of every following session. */
    public void setTracing(boolean on) { tracing = on; }

    public RewritingTrace lastTrace() { return lastTrace; }

    /** Number of hits per round of the recorded schedule (for reports). */
    public List<Integer> hitsPerRound(CoverageMode mode) {
        return schedule(mode).rounds().stream().map(r -> r.length).toList();
    }

    /** Records the schedule for {@code mode} on the current model (if not already recorded) and checks the replay. */
    public void prepare(CoverageMode mode) {
        schedule(mode);
    }

    private synchronized Schedule schedule(CoverageMode mode) {
        String fp = fingerprint();
        Schedule s = schedules.get(mode);
        if (s != null && s.fingerprint().equals(fp)) return s;
        s = record(mode, fp);
        schedules.put(mode, s);
        Map<String, Coverage> replayed = replay(mode, s).coverage();
        for (var e : s.expected().entrySet()) {
            if (replayed.getOrDefault(e.getKey(), Coverage.UNCOVERED) != e.getValue()) {
                throw new IllegalStateException("Oracle replay differs from the recording at " + e.getKey());
            }
        }
        return s;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        Schedule s = schedule(mode);
        RewritingReadout readout = replay(mode, s);
        return new RewritingSession(mode, input.model().getRelations(), readout);
    }

    // ------------------------------------------------------------------------------------------------------------

    private RewritingReadout replay(CoverageMode mode, Schedule s) {
        long t0 = System.nanoTime();
        PureBigraph agent = CoverageBigraph.build(input.bigraph(), mode);
        long tBuild = System.nanoTime() - t0, tMatch = 0, tRewrite = 0;
        int agentNodes = agent.getNodes().size(), calls = 0, r = 0;
        PureBigraphMatcher matcher = new PureBigraphMatcher();
        PureReactiveSystem reactions = new PureReactiveSystem();
        RewritingTrace trace = tracing ? new RewritingTrace() : null;
        boolean firstMatch = recorded.firstMatch() && ScheduledRewritingCoverageAnalyzer.FIRST_MATCH_WORKS;

        for (int[] round : s.rounds()) {
            r++;
            long roundStart = trace == null ? 0 : trace.now(), rewriteInRound = 0;
            int roundAgent = agent.getNodes().size();
            for (int i : round) {
                CompiledRule rule = RULES.get(i);
                calls++;
                t0 = System.nanoTime();
                long callStart = trace == null ? 0 : trace.now();
                Iterator<PureBigraphMatch> it = (firstMatch ? matcher.matchFirst(agent, rule.rule())
                        : matcher.matchAll(agent, rule.rule())).iterator();
                if (!it.hasNext()) throw new IllegalStateException("Oracle replay: " + rule.name() + " has no match");
                PureBigraphMatch match = it.next();
                tMatch += System.nanoTime() - t0;
                if (trace != null) trace.call(r, RewritingTrace.Kind.RULE, rule.name(), true, true, callStart, trace.now());
                t0 = System.nanoTime();
                agent = reactions.buildParametricReaction(agent, match, rule.rule());
                long rw = System.nanoTime() - t0;
                tRewrite += rw;
                rewriteInRound += rw;
            }
            // active = the rules the oracle lets through (all hits)
            if (trace != null) trace.round(r, round.length, 0, 0, roundStart, trace.now(), rewriteInRound, roundAgent,
                    RewritingReadout.ofRewritten(agent).coverage());
        }
        t0 = System.nanoTime();
        RewritingReadout readout = RewritingReadout.ofRewritten(agent);
        long tReadout = System.nanoTime() - t0;
        lastTrace = trace;
        lastStats = new RewritingStats(s.rounds().size(), calls, calls, agentNodes, tBuild, 0, tMatch, tRewrite, tReadout);
        return readout;
    }

    /** The schedule's real evaluation, traced; its hitting rule calls, round by round, are the recording. */
    private Schedule record(CoverageMode mode, String fp) {
        var rounds = new ScheduledRewritingCoverageAnalyzer(input, recorded);
        rounds.setTracing(true);
        CoverageSession session = rounds.session(mode);
        RewritingTrace trace = rounds.lastTrace();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < RULES.size(); i++) index.put(RULES.get(i).name(), i);
        List<int[]> schedule = new ArrayList<>();
        for (RewritingTrace.Round r : trace.rounds()) {
            int[] hits = trace.calls().stream()
                    .filter(c -> c.round() == r.round() && c.kind() == RewritingTrace.Kind.RULE && c.hit())
                    .mapToInt(c -> index.get(c.name())).toArray();
            if (hits.length > 0) schedule.add(hits);
        }
        Map<String, Coverage> expected = new HashMap<>();
        for (TreeNode<?> n : input.model().getAllNodes()) expected.put(n.getId(), session.coverage(n));
        return new Schedule(fp, List.copyOf(schedule), Map.copyOf(expected));
    }

    /** Identifies the model revision the schedule belongs to: nodes, their parents and the relations. */
    private String fingerprint() {
        StringBuilder sb = new StringBuilder();
        List<TreeNode<?>> nodes = new ArrayList<>(input.model().getAllNodes());
        nodes.sort(Comparator.comparing(TreeNode::getId));
        for (TreeNode<?> n : nodes) {
            sb.append(n.getId()).append('<').append(n.getParent().map(p -> ((TreeNode<?>) p).getId()).orElse(""))
                    .append(':').append(n.getDecompositionType()).append(';');
        }
        List<String> rels = new ArrayList<>();
        for (Relation r : input.model().getRelations().all()) rels.add(r.type() + ":" + r.source().getId() + ">" + r.target().getId());
        Collections.sort(rels);
        rels.forEach(sb::append);
        return sb.toString();
    }
}
