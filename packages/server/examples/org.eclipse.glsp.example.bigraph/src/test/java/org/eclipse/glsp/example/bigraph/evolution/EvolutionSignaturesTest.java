package org.eclipse.glsp.example.bigraph.evolution;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.utils.BigraphUtil;
import org.junit.jupiter.api.Test;

class EvolutionSignaturesTest {

    @Test
    void unionLeftKeepsSharedControlsWhenListsDifferInOrderAndLength() {
        DynamicSignature agent = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Door", 2),
            new ControlSpec("Robot", 1),
            new ControlSpec("Left", 1),
            new ControlSpec("Right", 1));
        DynamicSignature rule = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Door", 2),
            new ControlSpec("Robot", 1),
            new ControlSpec("Robot_door1", 1),
            new ControlSpec("Door_Robot1", 1),
            new ControlSpec("Left", 1),
            new ControlSpec("Right", 1),
            new ControlSpec("Allocated", 0));

        DynamicSignature merged = EvolutionSignatures.unionLeft(List.of(agent, rule, rule));

        assertNotNull(merged.getControlByName("Left"));
        assertEquals(8, merged.getControls().size());
    }

    @Test
    void frameworkMergeDropsASharedControlPastTheShorterList() {
        // Shared Left sits at index 7 of the longer side; zip stops at length 7.
        DynamicSignature longer = signature(
            new ControlSpec("Allocated", 0),
            new ControlSpec("Door", 2),
            new ControlSpec("Door_Robot1", 1),
            new ControlSpec("Right", 1),
            new ControlSpec("Robot", 1),
            new ControlSpec("Robot_door1", 1),
            new ControlSpec("Room", 0),
            new ControlSpec("Left", 1));
        DynamicSignature shorter = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Door", 2),
            new ControlSpec("Robot", 1),
            new ControlSpec("Robot_door1", 1),
            new ControlSpec("Door_Robot1", 1),
            new ControlSpec("Right", 1),
            new ControlSpec("Left", 1));

        DynamicSignature framework = BigraphUtil.mergeSignatures(longer, shorter, 0);
        DynamicSignature ours = EvolutionSignatures.unionLeft(List.of(longer, shorter));

        assertNull(framework.getControlByName("Left"));
        assertNotNull(ours.getControlByName("Left"));
    }

    @Test
    void unionLeftKeepsTheFirstArityWhenControlNamesConflict() {
        DynamicSignature agent = signature(new ControlSpec("Room", 2));
        DynamicSignature rule = signature(new ControlSpec("Room", 1));

        DynamicSignature merged = EvolutionSignatures.unionLeft(List.of(agent, rule));

        assertEquals(1, merged.getControls().size());
        assertEquals(2, merged.getControlByName("Room").getArity().getValue().intValue());
    }

    @Test
    void usedControlsOmitsUnusedMergedLabels() {
        DynamicSignature merged = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Left", 1),
            new ControlSpec("Allocated", 0));
        PureBigraphMutable bigraph = emptyBigraph(merged);
        addNode(bigraph, firstRoot(bigraph), "Room", "room1");
        addNode(bigraph, firstRoot(bigraph), "Left", "left1");

        DynamicSignature used = EvolutionSignatures.usedControls(bigraph);
        assertEquals(List.of("Room", "Left"), used.getControls().stream()
            .map(control -> control.getNamedType().stringValue())
            .collect(Collectors.toList()));
        assertNull(used.getControlByName("Allocated"));
    }

    @Test
    void rebindPreservesLinksHierarchyAndSites() throws Exception {
        DynamicSignature agent = signature(
            new ControlSpec("Room", 2),
            new ControlSpec("Sensor", 1));
        DynamicSignature rule = signature(
            new ControlSpec("Room", 2),
            new ControlSpec("Sensor", 1),
            new ControlSpec("Allocated", 0));
        DynamicSignature merged = EvolutionSignatures.unionLeft(List.of(agent, rule));

        PureBigraphMutable original = emptyBigraph(agent);
        var room = addNode(original, firstRoot(original), "Room", "r1");
        var site = original.addSite(room);
        var sensor = addNode(original, site, "Sensor", "s1");
        var shared = original.addOuterName("shared");
        original.connectNodeToLink(room, shared);
        original.connectNodeToLink(sensor, shared);
        // Room port 1 stays idle on purpose.

        PureBigraph rebound = EvolutionSignatures.rebind(original, merged);

        assertEquals(2, rebound.getNodes().size());
        assertEquals(1, rebound.getSites().size());
        assertEquals(1, rebound.getOuterNames().size());
        assertEquals("shared", rebound.getOuterNames().iterator().next().getName());

        var reboundRoom = nodeWithControl(rebound, "Room");
        var reboundSensor = nodeWithControl(rebound, "Sensor");
        // Hierarchy: the site sits under the room and contains the sensor.
        var reboundSite = rebound.getSites().get(0);
        assertEquals(reboundRoom, rebound.getParent(reboundSite));
        assertTrue(rebound.getChildrenOf(reboundSite).contains(reboundSensor));
        // Links: both connected points still share the one outer name, port 1 still idle.
        assertEquals("shared", rebound.getLinkOfPoint(rebound.getPorts(reboundRoom).get(0)).getName());
        assertEquals("shared", rebound.getLinkOfPoint(rebound.getPorts(reboundSensor).get(0)).getName());
        assertNull(rebound.getLinkOfPoint(rebound.getPorts(reboundRoom).get(1)));
    }

    @Test
    void rebindKeepsOuterNamesWithoutPoints() throws Exception {
        // An outer name nothing links to is still part of the outer interface.
        // The serialize/reload roundtrip must not silently narrow that face,
        // otherwise redex and reactum of a rule can end up with unequal outers.
        DynamicSignature sig = signature(new ControlSpec("Room", 1));
        PureBigraphMutable original = emptyBigraph(sig);
        var room = addNode(original, firstRoot(original), "Room", "r1");
        original.connectNodeToLink(room, original.addOuterName("door"));
        original.addOuterName("spare");

        PureBigraph rebound = EvolutionSignatures.rebind(original, sig);

        assertEquals(
            java.util.Set.of("door", "spare"),
            rebound.getOuterNames().stream()
                .map(outer -> outer.getName())
                .collect(Collectors.toSet()));
        assertEquals("door", rebound.getLinkOfPoint(
            rebound.getPorts(nodeWithControl(rebound, "Room")).get(0)).getName());
    }

    @Test
    void rebindResolvesControlsAgainstTheMergedSignature() throws Exception {
        DynamicSignature agent = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Left", 1));
        DynamicSignature rule = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Left", 1),
            new ControlSpec("Allocated", 0));
        DynamicSignature merged = EvolutionSignatures.unionLeft(List.of(agent, rule));

        PureBigraphMutable workspace = emptyBigraph(agent);
        addNode(workspace, firstRoot(workspace), "Room", "room1");
        addNode(workspace, firstRoot(workspace), "Left", "left1");

        PureBigraph rebound = EvolutionSignatures.rebind(workspace, merged);
        for (var node : rebound.getNodes()) {
            DynamicControl control = node.getControl();
            assertNotNull(control);
            assertNotNull(merged.getControlByName(control.getNamedType().stringValue()));
        }
        assertEquals(2, rebound.getNodes().size());
    }

    private static org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity<DynamicControl> nodeWithControl(
            final PureBigraph bigraph, final String control) {
        return bigraph.getNodes().stream()
            .filter(node -> control.equals(node.getControl().getNamedType().stringValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No node with control " + control));
    }
}
