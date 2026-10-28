package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;
import static org.junit.jupiter.api.Assertions.*;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

/** {@link GuardEffects} on small hand-made rules: exact effects, and the cases it must call unknown. */
class GuardEffectsTest {

    private static final DynamicSignature SIG = pureSignatureBuilder()
            .add("A", 0).add("B", 0).add("U", 0).add("P", 0).add("S", 1).create();

    private static PureBigraph bigraph(Consumer<PureBigraphBuilder<DynamicSignature>.Hierarchy> body) {
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(SIG);
        body.accept(builder.root());
        return builder.create();
    }

    /** A rule whose nodes of control {@code kept} are tracked from redex to reactum (one each). */
    private static ParametricReactionRule<PureBigraph> rule(PureBigraph redex, PureBigraph reactum, String... kept) throws Exception {
        ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, reactum);
        TrackingMap tracking = new TrackingMap();
        for (BigraphEntity.NodeEntity<?> node : reactum.getNodes()) {
            String control = node.getControl().getNamedType().stringValue();
            String from = "";
            for (String keptControl : kept) {
                if (!keptControl.equals(control)) continue;
                for (BigraphEntity.NodeEntity<?> redexNode : redex.getNodes()) {
                    if (redexNode.getControl().getNamedType().stringValue().equals(control)) from = redexNode.getName();
                }
            }
            tracking.put(node.getName(), from);
        }
        rule.withTrackingMap(tracking);
        return rule;
    }

    /** A[U | -] -> A[P | -], A kept. */
    private static ParametricReactionRule<PureBigraph> raise() throws Exception {
        return rule(bigraph(root -> root.child("A").down().child("U").site()),
                bigraph(root -> root.child("A").down().child("P").site()), "A");
    }

    @Test
    void aRuleThatReplacesAMarkerDestroysAndCreatesOneOccurrence() throws Exception {
        assertEquals(-1, GuardEffects.effect(raise(), bigraph(root -> root.child("A").down().child("U").site())));
        assertEquals(1, GuardEffects.effect(raise(), bigraph(root -> root.child("A").down().child("P").site())));
        assertEquals(0, GuardEffects.effect(raise(), bigraph(root -> root.child("A").down().site())));
        assertEquals(0, GuardEffects.effect(raise(), bigraph(root -> root.child("B").down().site())));
    }

    @Test
    void anOccurrenceThatReachesIntoAParameterIsUnknown() throws Exception {
        // A[U | U | -]: one U from the redex, the other one possibly in the parameter
        assertNull(GuardEffects.effect(raise(), bigraph(root -> root.child("A").down().child("U").child("U").site())));
    }

    @Test
    void aGuardWithLinkConstraintsIsUnknown() throws Exception {
        PureBigraph twoOnOneLink = bigraph(root -> {
            try {
                root.child("S", "x").child("S", "x");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNull(GuardEffects.effect(raise(), twoOnOneLink));
    }

    @Test
    void deletingANodeInARegionIsUnknownForGuardsWithExactChildren() throws Exception {
        // B -> nothing: the context node above B loses a child, so an exact A[] there may start to occur
        ParametricReactionRule<PureBigraph> delete = rule(bigraph(root -> root.child("B")), bigraph(root -> {}));
        assertNull(GuardEffects.effect(delete, bigraph(root -> root.child("A"))));
        assertEquals(0, GuardEffects.effect(delete, bigraph(root -> root.child("A").down().site())));
    }
}
