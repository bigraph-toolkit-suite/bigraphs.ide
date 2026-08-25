package org.eclipse.glsp.example.bigraph.extensions;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.variantGateFor;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import org.eclipse.glsp.graph.GraphFactory;
import org.junit.jupiter.api.Test;

import com.google.inject.Guice;

/**
 * Regression test for the single core-owned edge-creation checker.
 *
 * <p>In the classic bigraph variant, dynamic core edge types must stay
 * drawable (default-allow, mirroring GLSP without a bound checker).
 */
class ExtensionAwareEdgeCreationCheckerTest {

    private static final String CORE_DYNAMIC_EDGE_TYPE = "bigraph:link";

    @Test
    void coreVariantAllowsDynamicCoreEdges() {
        BigraphMetaInformation meta = new BigraphMetaInformation();
        meta.setModelType(CoreIdeExtension.BIGRAPH_VARIANT_ID);
        BigraphModelState state = initializedState(
                emptyBigraph(signature(new ControlSpec("A", 1))), meta);

        ExtensionAwareEdgeCreationChecker checker =
                new ExtensionAwareEdgeCreationChecker(variantGateFor(state), Guice.createInjector());

        var element = GraphFactory.eINSTANCE.createGNode();
        assertTrue(checker.isValidSource(CORE_DYNAMIC_EDGE_TYPE, element),
                "core dynamic edges must not be blocked by extension checkers");
        assertTrue(checker.isValidTarget(CORE_DYNAMIC_EDGE_TYPE, element, element));
    }
}
