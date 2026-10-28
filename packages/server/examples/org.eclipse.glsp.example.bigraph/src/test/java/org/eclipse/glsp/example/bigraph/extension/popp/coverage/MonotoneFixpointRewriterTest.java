package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.*;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.MonotoneFixpointRewriter.Result;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.junit.jupiter.api.Test;

/**
 * The generic {@link MonotoneFixpointRewriter} with the coverage rules: the generic overlap analysis finds every
 * dependency the coverage-specific one finds, and the engine reaches the coverage of the domain analyzer with either
 * analysis, either worklist order and either guard evaluator.
 */
class MonotoneFixpointRewriterTest {

    /** The coverage rules with the analyzer's guards as pattern guards, one {@link Guard} object per guard key. */
    private static List<Rule> coverageRules() {
        return RewritingCoverageAnalyzer.rewriterRules(true);
    }

    private static Matcher matcher() {
        return RewritingCoverageAnalyzer.FIRST_MATCH_WORKS ? Matcher.matchFirst() : Matcher.matchAll();
    }

    /** The link rules of {@code rewriter} (the seed of a session from scratch). */
    private static List<Rule> seed(MonotoneFixpointRewriter rewriter) {
        List<Rule> seed = new ArrayList<>();
        for (int i = 0; i < rewriter.rules().size(); i++) if (RewritingCoverageAnalyzer.rules().get(i).isLinkRule()) seed.add(rewriter.rules().get(i));
        return seed;
    }

    @Test
    void theGenericOverlapContainsTheCoverageSpecificOne() {
        List<Rule> rules = coverageRules();
        boolean[][] specific = CoverageRewriteRules.enablingByOverlap();
        boolean[][] generic = new OverlapProduceUseAnalyzer().analyzeRuleSet(rules);
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            for (int j = 0; j < rules.size(); j++) {
                if (specific[i][j] && !generic[i][j]) missing.add(rules.get(i).name() + " -> " + rules.get(j).name());
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theEngineReachesTheCoverageOfTheDomainAnalyzer() {
        List<Rule> rules = coverageRules();
        Matcher matcher = matcher();
        Map<String, MonotoneFixpointRewriter> variants = new LinkedHashMap<>();
        variants.put("generic overlap, fifo", MonotoneFixpointRewriter.builder(rules)
                .withProduceUseAnalyzer(new OverlapProduceUseAnalyzer()).withMatcher(matcher).build());
        variants.put("coverage overlap, topological", MonotoneFixpointRewriter.builder(rules)
                .withProduceUseAnalyzer(ProduceUseAnalyzer.of(CoverageRewriteRules.enablingByOverlap()))
                .withOrder(WorklistOrder.topological()).withMatcher(matcher).build());
        variants.put("generic overlap, topological, counted guards", MonotoneFixpointRewriter.builder(rules)
                .withProduceUseAnalyzer(new OverlapProduceUseAnalyzer()).withOrder(WorklistOrder.topological())
                .withGuardEvaluator(GuardEvaluator.counting()).withMatcher(matcher).build());

        for (int modelIndex = 0; modelIndex < 25; modelIndex++) {
            Synced popp = new Synced();
            CoverageRandomModels.build(popp, new Random(4200 + modelIndex), 25);
            CoverageInput in = CoverageInput.of(popp.model, popp.bigraph);
            for (CoverageMode mode : CoverageMode.presets()) {
                CoverageSession want = new DomainCoverageAnalyzer(in).session(mode);
                boolean[] usable = RewritingCoverageAnalyzer.usable(mode);
                for (var variant : variants.entrySet()) {
                    MonotoneFixpointRewriter rewriter = variant.getValue();
                    PureBigraph agent = CoverageBigraph.build(popp.bigraph, mode);
                    Result result = rewriter.rewriteToFixpoint(agent, seed(rewriter), rule -> usable[rewriter.indexOf(rule)],
                            2 * agent.getNodes().size() + 2);
                    CoverageSession got = new RewritingSession(mode, popp.model.getRelations(),
                            RewritingReadout.ofRewritten(result.agent()));
                    for (TreeNode<?> node : popp.model.getAllNodes()) {
                        assertEquals(want.coverage(node), got.coverage(node),
                                variant.getKey() + ", model " + modelIndex + ", " + mode + ", " + node.getId());
                    }
                }
            }
        }
    }

    /**
     * Counted guards stay exact: after every rewrite, every count the {@link GuardEvaluator#counting()} evaluator
     * keeps equals the number of occurrences the matcher finds.
     */
    @Test
    void countedGuardsEqualARecountAfterEveryRewrite() {
        for (List<Rule> rules : List.of(coverageRules())) {
            GuardEvaluator.Counting[] session = new GuardEvaluator.Counting[1];
            GuardEvaluator counting = GuardEvaluator.counting();
            MonotoneFixpointRewriter rewriter = MonotoneFixpointRewriter.builder(rules).withMatcher(matcher())
                    .withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                    .withGuardEvaluator(run -> session[0] = (GuardEvaluator.Counting) counting.start(run)).build();
            for (int modelIndex = 0; modelIndex < 3; modelIndex++) {
                Synced popp = new Synced();
                if (modelIndex == 0) CoverageTestModels.fullModel(popp.model, 1, true);
                else CoverageRandomModels.build(popp, new Random(300 + modelIndex), 15);
                PureBigraph start = CoverageBigraph.build(popp.bigraph, CoverageMode.VERIFY);
                PureBigraph[] agent = {start};
                int[] checked = {0};
                String where = "model " + modelIndex;
                RewriteListener check = new RewriteListener() {
                    @Override public void rewritten(Rule rule, PureBigraph rewritten, long nanos) { agent[0] = rewritten; }
                    @Override public void taken(Rule rule) { verify(); }
                    @Override public void runFinished(Result result) { verify(); }

                    void verify() {
                        Integer[] counts = session[0].counts();
                        for (int guard = 0; guard < counts.length; guard++) {
                            if (counts[guard] == null) continue;
                            checked[0]++;
                            assertEquals(matcher().occurrences(agent[0], rewriter.guards().get(guard).pattern()), counts[guard],
                                    rewriter.guards().get(guard).label() + ", " + where);
                        }
                    }
                };
                rewriter.rewriteToFixpoint(start, seed(rewriter), rule -> true, 2 * start.getNodes().size() + 2, check);
                assertTrue(checked[0] > 0);
            }
        }
    }

    /** A subclass that overrides one step, and a listener, see the same run as the result's counters. */
    @Test
    void hooksAndListenersSeeEveryCall() {
        int[] found = {0};
        MonotoneFixpointRewriter rewriter = MonotoneFixpointRewriter.builder(coverageRules())
                .withProduceUseAnalyzer(ProduceUseAnalyzer.of(CoverageRewriteRules.enablingByOverlap())).withMatcher(matcher())
                .build(analysis -> new MonotoneFixpointRewriter(analysis) {
                    @Override
                    protected Optional<PureBigraphMatch> findMatch(Run run, int rule) {
                        Optional<PureBigraphMatch> match = super.findMatch(run, rule);
                        if (match.isPresent()) found[0]++;
                        return match;
                    }
                });
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 1, true);
        PureBigraph agent = CoverageBigraph.build(popp.bigraph, CoverageMode.VERIFY);
        RewriteTrace trace = new RewriteTrace();
        Result result = rewriter.rewriteToFixpoint(agent, seed(rewriter), rule -> true, 2 * agent.getNodes().size() + 2, trace);
        assertTrue(result.rewrites() > 0);
        assertEquals(result.rewrites(), found[0]);
        assertEquals(result.rewrites(), trace.count(RewriteTrace.Kind.REWRITE));
        assertEquals(result.ruleCalls(), trace.count(RewriteTrace.Kind.RULE));
        assertEquals(result.rewrites(), trace.count(RewriteTrace.Kind.RULE, true));
        assertEquals(result.guardCalls(), trace.count(RewriteTrace.Kind.GUARD));
    }

    @Test
    void theTopologicalOrderRanksAComponentBeforeTheOnesItEnables() {
        // 0 -> 1 <-> 2 -> 3
        boolean[][] enables = new boolean[4][4];
        enables[0][1] = enables[1][2] = enables[2][1] = enables[2][3] = true;
        int[] rank = WorklistOrder.topological().priorities(List.of(), enables);
        assertTrue(rank[0] < rank[1]);
        assertEquals(rank[1], rank[2]);
        assertTrue(rank[2] < rank[3]);
    }
}
