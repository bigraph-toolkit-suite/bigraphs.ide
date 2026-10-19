package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

/** The coverage modes: their presets, the kinds they report gaps for, and how their results relate. */
class CoverageModeTest {

    @Test
    void presetsAreFoundByName() {
        for (CoverageMode mode : CoverageMode.presets()) {
            assertSame(mode, CoverageMode.fromString(mode.name()).orElseThrow());
            assertSame(mode, CoverageMode.valueOf(mode.name()));
        }
        assertTrue(CoverageMode.fromString("SOMETHING").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CoverageMode.valueOf("SOMETHING"));
    }

    @Test
    void planningAndVerifyKeepTheirIntrinsicKinds() {
        assertEquals(EnumSet.of(NodeKind.SOLUTION, NodeKind.SUCCESS_CRITERIA), CoverageMode.PLANNING.emitters());
        assertEquals(EnumSet.of(NodeKind.SOLUTION), CoverageMode.VERIFY.emitters());
        assertTrue(CoverageMode.PLANNING.isIntrinsic(NodeKind.SUCCESS_CRITERIA));
        assertFalse(CoverageMode.VERIFY.isIntrinsic(NodeKind.SUCCESS_CRITERIA));
    }

    @Test
    void gapsAreReportedForTheKindsBetweenEmittersAndTargets() {
        assertEquals(Set.of(NodeKind.PROBLEM), CoverageMode.PROBLEMS_BY_GOALS.gapKinds());
        assertEquals(Set.of(NodeKind.SUCCESS_CRITERIA, NodeKind.GOAL, NodeKind.PROBLEM),
                CoverageMode.PROBLEMS_BY_PROOFS.gapKinds());
        assertEquals(Set.of(NodeKind.GOAL), CoverageMode.GOALS_BY_CRITERIA.gapKinds());
        // the whole-model modes: every coverable kind that is not intrinsically covered
        assertEquals(Set.of(NodeKind.SUCCESS_PROOF, NodeKind.GOAL, NodeKind.PROBLEM), CoverageMode.PLANNING.gapKinds());
        assertEquals(Set.of(NodeKind.SUCCESS_PROOF, NodeKind.SUCCESS_CRITERIA, NodeKind.GOAL, NodeKind.PROBLEM),
                CoverageMode.VERIFY.gapKinds());
    }

    @Test
    void aModeWithoutEmittersReachesNothing() {
        CoverageMode none = new CoverageMode("NONE", "none", Set.of(), Set.of(NodeKind.PROBLEM));
        assertTrue(none.gapKinds().isEmpty());
    }

    /**
     * On random models (Domain analyzer):
     * <ul>
     *   <li>"goals by criteria" equals planning on goals and problems (solutions add nothing there);</li>
     *   <li>"problems by proofs" never exceeds "problems by goals" (coverage reaches problems only through goals), so
     *   covered by proofs implies covered by goals and proofs;</li>
     *   <li>verify never exceeds "problems by proofs" (it only counts the proofs that solutions produce).</li>
     * </ul>
     */
    @Test
    void theModesAreOrderedAsTheTaskExpects() {
        for (int i = 0; i < 300; i++) {
            Synced popp = new Synced();
            CoverageRandomModels.build(popp, new Random(7000 + i), 30);
            CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph));
            CoverageSession planning = analyzer.session(CoverageMode.PLANNING);
            CoverageSession verify = analyzer.session(CoverageMode.VERIFY);
            CoverageSession byCriteria = analyzer.session(CoverageMode.GOALS_BY_CRITERIA);
            CoverageSession byGoals = analyzer.session(CoverageMode.PROBLEMS_BY_GOALS);
            CoverageSession byProofs = analyzer.session(CoverageMode.PROBLEMS_BY_PROOFS);
            for (TreeNode<?> n : popp.model.getAllNodes()) {
                String where = "model " + i + ", " + n.getDescription();
                if (n.getKind() == NodeKind.GOAL || n.getKind() == NodeKind.PROBLEM) {
                    assertEquals(planning.coverage(n), byCriteria.coverage(n), where);
                }
                if (n.getKind() == NodeKind.PROBLEM) {
                    assertTrue(byProofs.coverage(n).compareTo(byGoals.coverage(n)) <= 0, where);
                    assertTrue(verify.coverage(n).compareTo(byProofs.coverage(n)) <= 0, where);
                }
            }
        }
    }

    @Test
    void theRewritingAnalyzerAgreesWithTheDomainAnalyzerInEveryPreset() {
        StringBuilder report = new StringBuilder();
        assertEquals(0, CoverageRandomModels.mismatches("fragment", 100, 25, 9100, report), report.toString());
    }
}
