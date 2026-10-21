package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.StructuralPrefilter;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageMarker;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.junit.jupiter.api.Test;

import java.util.*;

/** The prefilter never rejects a rule the matcher matches (soundness), and on coverage agents it is exact. */
class StructuralPrefilterTest {

    @Test
    void neverRejectsAMatchAndIsExactOnCoverageAgents() {
        List<CompiledRule> rules = CoverageRewriteRules.compile();
        List<StructuralPrefilter.Structure> patterns = rules.stream()
                .map(r -> StructuralPrefilter.compile(r.rule().getRedex())).toList();
        PureBigraphMatcher matcher = new PureBigraphMatcher();
        Coverage[] values = Coverage.values();
        int checked = 0, rejectedMatches = 0, letThroughMisses = 0;
        for (int i = 0; i < 6; i++) {
            Synced popp = new Synced();
            Random rnd = new Random(900 + i);
            CoverageRandomModels.build(popp, rnd, 8);
            // random states, so that agents with every combination of cells occur, not only reachable ones
            PureBigraphMutable agent = CoverageBigraph.build(popp.bigraph, CoverageMode.PLANNING);
            for (TreeNode<?> n : popp.model.getAllNodes()) setState(agent, n.getId(), values[rnd.nextInt(values.length)]);
            StructuralPrefilter.Structure structure = StructuralPrefilter.compile(agent);
            for (int r = 0; r < rules.size(); r++) {
                boolean matches = matcher.matchAll(agent, rules.get(r).rule()).iterator().hasNext();
                boolean may = StructuralPrefilter.mayMatch(patterns.get(r), structure);
                checked++;
                if (matches && !may) rejectedMatches++;
                if (!matches && may) letThroughMisses++;
            }
        }
        assertEquals(0, rejectedMatches, "prefilter rejected a rule that matches (" + checked + " checks)");
        assertEquals(0, letThroughMisses, "prefilter let a miss through (" + checked + " checks)");
    }

    /** Replaces the state cell of the tree node {@code name} in a freshly built coverage bigraph. */
    private static void setState(PureBigraphMutable g, String name, Coverage state) {
        var node = g.getNodes().stream().filter(n -> n.getName().equals(name)).findFirst().orElseThrow();
        for (BigraphEntity<?> c : new ArrayList<>(g.getChildrenOf(node))) {
            if (c instanceof BigraphEntity.NodeEntity<?> m && CoverageMarker.forNode(m).flatMap(CoverageMarker::state).isPresent()) {
                g.removeNode((BigraphEntity.NodeEntity) m);
            }
        }
        g.addNode(node, g.getSignature().getControlByName(CoverageMarker.of(state).controlName()), name + ".state");
    }
}
