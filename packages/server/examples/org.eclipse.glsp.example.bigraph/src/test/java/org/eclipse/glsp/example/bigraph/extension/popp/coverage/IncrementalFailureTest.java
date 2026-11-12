package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.*;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.junit.jupiter.api.Test;

/**
 * A session that fails while the incremental analyzer replays an edit (e.g. a matcher error) must not corrupt the
 * next one: the mirror edits the coverage bigraph in place, so replaying the same operations again would apply them
 * twice (seen as "Node has reached its arity limit" after a failed session).
 */
class IncrementalFailureTest {
    private static final long SEED = 4711;

    /** Fails the next rule call once {@link #armed} is set. */
    private static final class FailingListener implements RewriteListener {
        boolean armed;

        int injected;

        @Override
        public void taken(Rule rule) {
            if (armed) {
                armed = false;
                injected++;
                throw new IllegalStateException("injected matcher failure");
            }
        }
    }

    @Test
    void theSessionAfterAFailedOneStartsAgainAndIsCorrect() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITING_SUPPORTED, "needs the patched framework");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 1, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        FailingListener failing = new FailingListener();
        MonotoneFixpointRewriter rewriter = MonotoneFixpointRewriter.builder(CoverageRuleSet.DEFAULT.rewriterRules(true))
                .withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                .withOrder(WorklistOrder.topological())
                .withGuardEvaluator(GuardEvaluator.counting())
                .withListener(failing)
                .build();
        var analyzer = new IncrementalRewritingCoverageAnalyzer(input, new RewritingCoverageAnalyzer(input, rewriter));
        popp.bigraph.addListener(analyzer);
        CoverageMode mode = CoverageMode.VERIFY;
        analyzer.session(mode);

        EditScript script = new EditScript(popp.model, SEED);
        for (EditScript.Action action : EditScript.Action.values()) {
            script.reseed(SEED + action.ordinal());
            String what = script.step(action);
            failing.armed = true;
            try {
                analyzer.session(mode); // an edit that queues no rule is replayed without a rule call, and does not fail
            } catch (IllegalStateException expected) {
                // the injected failure
            }
            failing.armed = false;
            check(popp, mode, analyzer.session(mode), "after a failed session, " + what);
            script.undo(); // the undo is replayed normally on top of the restarted mode
            check(popp, mode, analyzer.session(mode), "after undoing " + what);
        }
        assertTrue(failing.injected > 0, "no session failed, the test did not test anything");
    }

    /** Fails if {@code got} differs from the domain analyzer on any node. */
    private static void check(Synced popp, CoverageMode mode, CoverageSession got, String when) {
        CoverageSession want = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
        for (TreeNode<?> node : popp.model.getAllNodes()) {
            assertEquals(want.coverage(node), got.coverage(node), mode + " " + when + ": " + node.getDescription());
        }
    }
}
