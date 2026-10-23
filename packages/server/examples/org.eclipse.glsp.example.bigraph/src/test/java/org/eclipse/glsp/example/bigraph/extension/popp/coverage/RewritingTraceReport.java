package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;
import org.eclipse.emf.ecore.util.EcoreUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Traces one session per whole-agent variant on the benchmark models and writes them side by side as CSV, for
 * {@code plot_traces.py}: what was active, what was skipped, which calls hit or missed, round by round and over time.
 * Each variant runs {@code warmup} untraced sessions first, so the traced one is warm. Not a benchmark: tracing adds a
 * little overhead (a state census per round, and for the rewriting variants a readout per round).
 * <p>
 * An {@code incremental-<schedule>} variant traces the inspection after one edit instead, as in
 * {@link EditChainBreakdown}: from the benchmark model, edit {@code k} of {@code action} with seed {@code 4711 + warmup + k},
 * for {@code k < steps}, each inspected after a resync to the benchmark model; the traced step is the one of median reach
 * (states reset by the edit, ties to the earlier step), so the lane shows a typical edit rather than the cheapest or the
 * most expensive one. The reach depends on the edit only, so every incremental schedule traces the same edit. Its trace starts with the
 * engine run (the sync of the edit, well under a millisecond, is not on it).
 * <p>
 * {@code incremental-oracle} is the hits-only bound of that traced edit for the default schedule, the incremental
 * counterpart of {@link OracleRewritingCoverageAnalyzer}: the default incremental analyzer runs the same steps, a copy of
 * the synced coverage bigraph of the chosen step is kept, and the step's hitting rule calls are replayed on that copy,
 * round by round, with {@code matchFirst} (so the same matches in the same order), without guards, misses or worklist.
 * The replay is checked against the analyzer's result for the step.
 * <p>
 * {@code RewritingTraceReport <out-dir> [depths=0,1,2] [mode=VERIFY] [variants=...] [warmup=2] [action=MIXED] [steps=5]}
 * <p>
 * Writes {@code rounds.csv} (one row per variant, depth and round), {@code calls.csv} (one row per matcher call) and
 * {@code summary.csv} (one row per variant and depth).
 */
public final class RewritingTraceReport {
    static final List<String> DEFAULT = List.of("worklist", "worklist-seeded", "guards-first",
            "worklist-guards-seeded-first", "oracle");

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "trace");
        int[] depths = Arrays.stream((args.length > 1 ? args[1] : "0,1,2").split(",")).mapToInt(Integer::parseInt).toArray();
        CoverageMode mode = args.length > 2 ? CoverageMode.valueOf(args[2]) : CoverageMode.VERIFY;
        List<String> variants = args.length > 3 ? List.of(args[3].split(",")) : DEFAULT;
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        EditScript.Action action = EditScript.Action.valueOf(args.length > 5 ? args[5] : "MIXED");
        int steps = args.length > 6 ? Integer.parseInt(args[6]) : 5;
        Files.createDirectories(out);

        try (PrintWriter rounds = new PrintWriter(Files.newBufferedWriter(out.resolve("rounds.csv")));
             PrintWriter calls = new PrintWriter(Files.newBufferedWriter(out.resolve("calls.csv")));
             PrintWriter summary = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.csv")))) {
            rounds.println("variant,depth,popp_nodes,round,active,guarded,guard_calls,guard_pass,rule_calls,"
                    + "hits,misses,start_ms,duration_ms,build_ms,agent_nodes,uncovered,partial,covered");
            calls.println("variant,depth,popp_nodes,seq,round,kind,name,matched,start_ms,duration_ms");
            summary.println("variant,depth,popp_nodes,session_ms,rounds,guard_calls,rule_calls,hits,misses");
            System.out.printf("%-34s %5s %6s %10s %6s %7s %7s %5s %7s%n", "variant", "depth", "nodes", "session ms",
                    "rounds", "guards", "rules", "hits", "misses");

            for (int depth : depths) {
                for (String variant : variants) {
                    if (!variant.equals("domain") && !ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS) {
                        System.out.println("skipped " + variant + " (needs the fixed jLibBig AgentMatcher)");
                        continue;
                    }
                    Synced popp = new Synced();
                    TreeNode<?> root = CoverageTestModels.fullModel(popp.model, depth, true).root(NodeKind.PROBLEM);
                    int nodes = popp.model.getAllNodes().size();
                    boolean incOracle = variant.equals("incremental-oracle");
                    CoverageAnalyzer analyzer = CoverageTestModels.analyzer(incOracle ? "incremental" : variant, popp);
                    double sessionMs;
                    RewritingTrace trace;
                    if (incOracle) {
                        Traced replayed = incrementalOracle((IncrementalCoverageAnalyzer) analyzer, popp, root, mode,
                                action, warmup, steps);
                        sessionMs = replayed.ms;
                        trace = replayed.trace;
                        System.out.println("  " + variant + ", depth " + depth + ": replayed edit " + replayed.edit
                                + " (step " + replayed.k + ", " + replayed.reset + " states reset)");
                    } else if (analyzer instanceof IncrementalCoverageAnalyzer inc) {
                        Traced median = medianEdit(inc, popp, root, mode, action, warmup, steps);
                        sessionMs = median.ms;
                        trace = median.trace;
                        System.out.println("  " + variant + ", depth " + depth + ": traced edit " + median.edit + " (step " + median.k + ", " + median.reset + " states reset)");
                    } else {
                        if (analyzer instanceof OracleRewritingCoverageAnalyzer o) o.prepare(mode);
                        for (int i = 0; i < warmup; i++) analyzer.session(mode);
                        setTracing(analyzer, true);
                        long t = System.nanoTime();
                        analyzer.session(mode);
                        sessionMs = (System.nanoTime() - t) / 1e6;
                        setTracing(analyzer, false);
                        trace = lastTrace(analyzer);
                    }

                    String key = variant + "," + depth + "," + nodes;
                    int seq = 0;
                    for (RewritingTrace.Call c : trace.calls()) {
                        calls.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%b,%.4f,%.4f%n", key, seq++, c.round(), c.kind(),
                                c.name(), c.matched(), c.start() / 1e6, c.nanos() / 1e6);
                    }
                    int totalGuards = 0, totalRules = 0, totalHits = 0, totalMisses = 0;
                    for (RewritingTrace.Round r : trace.rounds()) {
                        int guardCalls = 0, guardPass = 0, ruleCalls = 0, hits = 0, misses = 0;
                        for (RewritingTrace.Call c : trace.calls()) {
                            if (c.round() != r.round()) continue;
                            if (c.kind() == RewritingTrace.Kind.GUARD) {
                                guardCalls++;
                                if (c.matched()) guardPass++;
                            } else {
                                ruleCalls++;
                                if (c.matched()) hits++;
                                else misses++;
                            }
                        }
                        totalGuards += guardCalls; totalRules += ruleCalls; totalHits += hits;
                        totalMisses += misses;
                        rounds.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%d,%d,%d,%d%n", key,
                                r.round(), r.queued(), r.guarded(), guardCalls, guardPass, ruleCalls,
                                hits, misses, r.start() / 1e6, r.nanos() / 1e6, r.buildNanos() / 1e6,
                                r.agentNodes(), r.uncovered(), r.partial(), r.covered());
                    }
                    summary.printf(Locale.ROOT, "%s,%.3f,%d,%d,%d,%d,%d%n", key, sessionMs, trace.rounds().size(),
                            totalGuards, totalRules, totalHits, totalMisses);
                    System.out.printf("%-34s %5d %6d %10.1f %6d %7d %7d %5d %7d%n", variant, depth, nodes, sessionMs,
                            trace.rounds().size(), totalGuards, totalRules, totalHits, totalMisses);
                    rounds.flush(); calls.flush(); summary.flush();
                }
            }
        }
        System.out.println("wrote " + out.resolve("rounds.csv") + ", calls.csv, summary.csv");
    }

    /** One traced step; {@code start}: the synced bigraph the engine started from (a copy), if it was kept. */
    private record Traced(int k, int reset, double ms, RewritingTrace trace, String edit, PureBigraph start,
                          Map<String, Coverage> result) {}

    private static final long SEED = 4711;

    /** The traced inspection after each of {@code steps} edits (seeds as in {@link EditChainBreakdown}); the one of median reach. */
    private static Traced medianEdit(IncrementalCoverageAnalyzer inc, Synced popp, TreeNode<?> root, CoverageMode mode,
                                     EditScript.Action action, int warmup, int steps) {
        return medianEdit(inc, popp, root, mode, action, warmup, steps, false);
    }

    private static Traced medianEdit(IncrementalCoverageAnalyzer inc, Synced popp, TreeNode<?> root, CoverageMode mode,
                                     EditScript.Action action, int warmup, int steps, boolean keepStart) {
        EditScript script = new EditScript(popp.model, SEED);
        inc.session(mode).coverage(root);
        PureBigraph[] start = new PureBigraph[1];
        if (keepStart) inc.beforeRewrite(agent -> start[0] = copy(agent));
        List<Traced> traced = new ArrayList<>();
        for (int k = -warmup; k < steps; k++) {
            script.undo();
            inc.session(mode).coverage(root); // resync to the benchmark model
            script.reseed(SEED + warmup + k);
            String edit = script.step(action);
            start[0] = null;
            inc.setTracing(k >= 0);
            long t = System.nanoTime();
            CoverageSession session = inc.session(mode);
            session.coverage(root);
            double ms = (System.nanoTime() - t) / 1e6;
            inc.setTracing(false);
            if (k < 0) continue;
            if (inc.lastStats().operations() == 0) continue; // the edit changed nothing (e.g. no decomposed node to retype)
            Map<String, Coverage> result = new HashMap<>();
            if (keepStart) for (TreeNode<?> n : popp.model.getAllNodes()) result.put(n.getId(), session.coverage(n));
            traced.add(new Traced(k, inc.lastStats().reset(), ms, inc.lastTrace(), edit, start[0], result));
        }
        inc.beforeRewrite(null);
        if (traced.isEmpty()) throw new IllegalStateException("No " + action + " edit of " + steps + " changed the model");
        traced.sort(Comparator.comparingInt(Traced::reset).thenComparingInt(Traced::k));
        return traced.get(traced.size() / 2);
    }

    /**
     * The incremental oracle of the default schedule for the edit {@link #medianEdit} picks: its hitting rule calls,
     * replayed on a copy of the synced bigraph that edit started from. Times and traces the replay only.
     */
    private static Traced incrementalOracle(IncrementalCoverageAnalyzer inc, Synced popp, TreeNode<?> root,
                                            CoverageMode mode, EditScript.Action action, int warmup, int steps) {
        Traced recorded = medianEdit(inc, popp, root, mode, action, warmup, steps, true);
        if (recorded.start == null) throw new IllegalStateException("The traced edit synced nothing: " + recorded.edit);
        Map<String, CoverageRewriteRules.CompiledRule> rules = new HashMap<>();
        for (CoverageRewriteRules.CompiledRule r : ScheduledRewritingCoverageAnalyzer.rules()) rules.put(r.name(), r);
        boolean firstMatch = ScheduledRewritingCoverageAnalyzer.Options.DEFAULT.firstMatch()
                && ScheduledRewritingCoverageAnalyzer.FIRST_MATCH_WORKS;
        PureBigraphMatcher matcher = new PureBigraphMatcher();
        PureReactiveSystem reactions = new PureReactiveSystem();
        RewritingTrace trace = new RewritingTrace();
        PureBigraph agent = recorded.start;
        long t = System.nanoTime();
        int r = 0;
        for (RewritingTrace.Round round : recorded.trace.rounds()) {
            List<String> hits = recorded.trace.calls().stream()
                    .filter(c -> c.round() == round.round() && c.hit()).map(RewritingTrace.Call::name).toList();
            if (hits.isEmpty()) continue;
            r++;
            long roundStart = trace.now(), rewrite = 0;
            int roundAgent = agent.getNodes().size();
            for (String name : hits) {
                CoverageRewriteRules.CompiledRule rule = rules.get(name);
                long callStart = trace.now();
                Iterator<PureBigraphMatch> it = (firstMatch ? matcher.matchFirst(agent, rule.rule())
                        : matcher.matchAll(agent, rule.rule())).iterator();
                if (!it.hasNext()) throw new IllegalStateException("Incremental oracle replay: " + name + " has no match");
                PureBigraphMatch match = it.next();
                trace.call(r, RewritingTrace.Kind.RULE, name, true, callStart, trace.now());
                long t0 = System.nanoTime();
                agent = reactions.buildParametricReaction(agent, match, rule.rule());
                rewrite += System.nanoTime() - t0;
            }
            trace.round(r, hits.size(), 0, roundStart, trace.now(), rewrite, roundAgent,
                    RewritingReadout.ofRewritten(agent).coverage());
        }
        RewritingSession replayed = new RewritingSession(mode, popp.model.getRelations(), RewritingReadout.ofRewritten(agent));
        double ms = (System.nanoTime() - t) / 1e6;
        for (TreeNode<?> n : popp.model.getAllNodes()) {
            if (replayed.coverage(n) != recorded.result.get(n.getId())) {
                throw new IllegalStateException("Incremental oracle replay differs from the analyzer at " + n.getId());
            }
        }
        return new Traced(recorded.k, recorded.reset, ms, trace, recorded.edit, null, null);
    }

    /** A copy of {@code g} with its own EMF instance model (the analyzer goes on editing and rewriting the original). */
    private static PureBigraph copy(PureBigraph g) {
        return PureBigraphBuilder.create(g.getSignature(), g.getMetaModel(), EcoreUtil.copy(g.getInstanceModel())).create();
    }

    static void setTracing(CoverageAnalyzer a, boolean on) {
        if (a instanceof ScheduledRewritingCoverageAnalyzer s) s.setTracing(on);
        else if (a instanceof OracleRewritingCoverageAnalyzer o) o.setTracing(on);
        else throw new IllegalArgumentException("Not a traceable whole-agent analyzer: " + a.getClass().getSimpleName());
    }

    static RewritingTrace lastTrace(CoverageAnalyzer a) {
        if (a instanceof ScheduledRewritingCoverageAnalyzer s) return s.lastTrace();
        return ((OracleRewritingCoverageAnalyzer) a).lastTrace();
    }
}
