package org.eclipse.glsp.example.bigraph.extension.popp.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.*;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

import java.util.*;

class POPPJsonTest {

    private static Synced fullModel(int depth) {
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, depth, false);
        // one OR decomposition, so both types occur
        popp.model.getProblemRoots().getFirst().setDecompositionType(DecompositionType.OR);
        return popp;
    }

    @Test
    void exportThenImportGivesTheSameModel() {
        Synced original = fullModel(2);
        String json = POPPJson.write(original.model);

        Synced copy = new Synced();
        POPPJson.apply(POPPJson.read(json), copy.model);

        assertEquals(json, POPPJson.write(copy.model));
        assertEquals(original.model.getAllNodes().size(), copy.model.getAllNodes().size());
        assertEquals(original.model.getRelations().all().size(), copy.model.getRelations().all().size());
        // the bigraph followed the import like any other edit
        assertEquals(original.bigraph.getBigraph().getNodes().size(), copy.bigraph.getBigraph().getNodes().size());
        assertEquals(original.bigraph.getBigraph().getEdges().size(), copy.bigraph.getBigraph().getEdges().size());
        for (CoverageMode mode : CoverageMode.presets()) {
            CoverageSession a = new DomainCoverageAnalyzer(CoverageInput.of(original.model, original.bigraph)).session(mode);
            CoverageSession b = new DomainCoverageAnalyzer(CoverageInput.of(copy.model, copy.bigraph)).session(mode);
            for (TreeNode<?> n : original.model.getAllNodes()) {
                assertEquals(a.coverage(n), b.coverage(copy.model.findNode(n.getId())), mode + " " + n.getDescription());
            }
        }
    }

    @Test
    void importReplacesTheModelAndTheIncrementalAnalysisFollows() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced target = fullModel(1);
        Set<String> oldIds = new HashSet<>();
        target.model.getAllNodes().forEach(n -> oldIds.add(n.getId()));
        CoverageAnalyzer incremental = CoverageTestModels.analyzer("incremental", target);
        incremental.session(CoverageMode.VERIFY); // a fixpoint of the old model, to be synced across the import

        String json = POPPJson.write(fullModel(2).model);
        POPPJson.apply(POPPJson.read(json), target.model);

        assertTrue(target.model.getAllNodes().stream().noneMatch(n -> oldIds.contains(n.getId())));
        assertEquals(json, POPPJson.write(target.model));
        CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(target.model, target.bigraph)).session(CoverageMode.VERIFY);
        CoverageSession actual = incremental.session(CoverageMode.VERIFY);
        for (TreeNode<?> n : target.model.getAllNodes()) {
            assertEquals(expected.coverage(n), actual.coverage(n), n.getDescription());
        }
    }

    @Test
    void layoutAndDecompositionAreOptional() {
        String json = """
                { "format": "popp", "version": 1,
                  "trees": {
                    "problems": [ { "id": "p", "description": "root",
                                    "children": [ { "id": "p1" }, { "id": "p2", "description": "two" } ] } ],
                    "goals": [ { "id": "g", "description": "goal" } ] },
                  "relations": [ { "type": "inverts", "source": "p", "target": "g" } ] }
                """;
        Synced popp = new Synced();
        POPPJson.apply(POPPJson.read(json), popp.model);
        TreeNode<?> root = popp.model.findNode("p");
        assertEquals(DecompositionType.AND, root.getDecompositionType());
        assertEquals(List.of("p1", "p2"), root.getChildren().stream().map(TreeNode::getId).toList());
        assertEquals("", popp.model.findNode("p1").getDescription());
        assertEquals(RelationType.INVERTS, popp.model.getRelations().all().iterator().next().type());
    }

    @Test
    void anInvalidDocumentReportsEveryProblem() {
        String json = """
                { "format": "popp", "version": 2,
                  "trees": {
                    "problems": [ { "id": "p", "decomposition": "or" }, { "id": "p" } ],
                    "goals": [ { "id": "g", "decomposition": "xor", "children": [ { "id": "g1" } ] } ],
                    "tasks": [] },
                  "relations": [ { "type": "realizes", "source": "p", "target": "g" },
                                 { "type": "inverts", "source": "p", "target": "nowhere" } ] }
                """;
        var e = assertThrows(POPPJson.InvalidDocumentException.class, () -> POPPJson.read(json));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("\"version\" must be 1"), all);
        assertTrue(all.contains("Unknown tree \"tasks\""), all);
        assertTrue(all.contains("only a node with children has a decomposition"), all);
        assertTrue(all.contains("duplicate id \"p\""), all);
        assertTrue(all.contains("\"decomposition\" must be \"and\" or \"or\""), all);
        assertTrue(all.contains("a realizes relation cannot go from a problem to a goal"), all);
        assertTrue(all.contains("unknown target node \"nowhere\""), all);
        assertEquals(7, e.problems().size(), all);
    }

    @Test
    void notJsonAtAll() {
        var e = assertThrows(POPPJson.InvalidDocumentException.class, () -> POPPJson.read("<xmi/>"));
        assertTrue(e.getMessage().contains("JSON"), e.getMessage());
    }
}
