package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.point;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPTestSession;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.*;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.InspectionResult;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPEdgeCreationChecker;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelTypes;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.MessageAction;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.eclipse.glsp.server.features.directediting.ApplyLabelEditOperation;
import org.eclipse.glsp.server.operations.CreateEdgeOperation;
import org.eclipse.glsp.server.operations.CreateNodeOperation;
import org.junit.jupiter.api.Test;

/** Every POPP action handler, run against a real session (model, GModel and bigraph wired as in the editor). */
class POPPActionHandlersTest {
    private final POPPTestSession session = new POPPTestSession();
    private final POPPModel model = session.model();
    private final POPPGModelFactory ids = new POPPGModelFactory();

    /** Injects what Guice would into a model-changing handler. */
    private <H extends AbstractPOPPActionHandler<?>> H mutating(H handler) {
        inject(handler, "context", session.context);
        inject(handler, "actionDispatcher", session.dispatcher);
        inject(handler, "variantGate", session.variantGate());
        return handler;
    }

    private <H> H reading(H handler) {
        inject(handler, "context", session.context);
        return handler;
    }

    private List<String> errors() {
        return session.dispatcher.dispatched(MessageAction.class).stream()
                .filter(m -> m.getSeverity().equalsIgnoreCase("ERROR")).map(MessageAction::getMessage).toList();
    }

    private static void assertModelUpdate(List<Action> result) {
        assertEquals(1, result.size());
        assertInstanceOf(UpdateModelAction.class, result.getFirst());
    }

    private TreeNode<?> node(NodeKind kind, String description) {
        return model.createNode(kind, description, 0, 0);
    }

    // -- AbstractPOPPActionHandler -----------------------------------------------------------------------------

    @Test
    void aSuccessfulChangeMarksTheModelDirtyAndUpdatesTheClient() {
        TreeNode<?> goal = node(NodeKind.GOAL, "old");
        session.dispatcher.clear();

        List<Action> result = mutating(new EditDescriptionNodeHandler())
                .executeAction(new ApplyLabelEditOperation(ids.toDescriptionId(goal), "new"));

        assertModelUpdate(result);
        assertSame(session.gmodelRoot(), ((UpdateModelAction) result.getFirst()).getNewRoot());
        assertTrue(session.dispatcher.dispatched(SetDirtyStateAction.class).getFirst().isDirty());
    }

    @Test
    void nothingHappensWhileAnotherVariantIsActive() {
        session.modelState.setActiveVariantId("bigraph");

        List<Action> result = mutating(new CreateNodeOperationHandler())
                .executeAction(new CreateNodeOperation("popp:goal", point(0, 0), null, Map.of("nodeType", "GOAL")));

        assertTrue(result.isEmpty());
        assertTrue(model.getAllNodes().isEmpty());
        assertTrue(errors().getFirst().contains("'bigraph'"), errors()::toString);
    }

    @Test
    void anUnexpectedExceptionBecomesAnErrorNotification() {
        session.state().setPoppModel(null); // the handler will fail on the missing model

        List<Action> result = mutating(new CreateNodeOperationHandler())
                .executeAction(new CreateNodeOperation("popp:goal", point(0, 0), null, Map.of("nodeType", "GOAL")));

        assertTrue(result.isEmpty());
        assertTrue(errors().getFirst().startsWith("POPP operation failed"), errors()::toString);
        assertTrue(session.dispatcher.dispatched(SetDirtyStateAction.class).isEmpty());
    }

    // -- CreateNodeOperationHandler ------------------------------------------------------------------------------

    @Test
    void createsANodeOfTheRequestedKindAtTheLocationAndOpensItsEditor() {
        List<Action> result = mutating(new CreateNodeOperationHandler()).executeAction(
                new CreateNodeOperation("popp:success_criteria", point(30, 40), null,
                        Map.of("nodeType", "SUCCESS_CRITERIA")));

        assertModelUpdate(result);
        TreeNode<?> created = model.getAllNodes().iterator().next();
        assertEquals(NodeKind.SUCCESS_CRITERIA, created.getKind());
        assertEquals(30, created.getX());
        assertEquals(40, created.getY());
        List<StartDescriptionEditAction> edit = session.dispatcher.afterNextUpdate(StartDescriptionEditAction.class);
        assertEquals(List.of(ids.toDescriptionId(created)), edit.stream().map(StartDescriptionEditAction::getLabelId).toList());
        assertTrue(session.gmodelElement(created.getId()).isPresent());
    }

    @Test
    void withoutALocationTheNodeIsPlacedAtTheOrigin() {
        mutating(new CreateNodeOperationHandler())
                .executeAction(new CreateNodeOperation("popp:goal", null, null, Map.of("nodeType", "GOAL")));
        TreeNode<?> created = model.getAllNodes().iterator().next();
        assertEquals(0, created.getX());
        assertEquals(0, created.getY());
    }

    @Test
    void anUnknownNodeTypeIsRejected() {
        List<Action> result = mutating(new CreateNodeOperationHandler())
                .executeAction(new CreateNodeOperation("popp:x", point(0, 0), null, Map.of("nodeType", "UNICORN")));

        assertTrue(result.isEmpty());
        assertTrue(model.getAllNodes().isEmpty());
        assertEquals(List.of("POPP: Invalid Node Type"), errors());
    }

    // -- EditDescriptionNodeHandler -------------------------------------------------------------------------------

    @Test
    void editingADescriptionLabelChangesTheNode() {
        TreeNode<?> goal = node(NodeKind.GOAL, "old");

        mutating(new EditDescriptionNodeHandler())
                .executeAction(new ApplyLabelEditOperation(ids.toDescriptionId(goal), "new"));

        assertEquals("new", goal.getDescription());
    }

    @Test
    void otherLabelsAreIgnored() {
        TreeNode<?> goal = node(NodeKind.GOAL, "old");

        List<Action> result = mutating(new EditDescriptionNodeHandler())
                .executeAction(new ApplyLabelEditOperation(goal.getId(), "new"));

        assertTrue(result.isEmpty());
        assertEquals("old", goal.getDescription());
    }

    @Test
    void aLabelOfAnUnknownNodeIsReported() {
        List<Action> result = mutating(new EditDescriptionNodeHandler())
                .executeAction(new ApplyLabelEditOperation("missing_description", "new"));

        assertTrue(result.isEmpty());
        assertEquals(List.of("POPP: Node not found"), errors());
    }

    // -- SwitchDecompositionTypeHandler ---------------------------------------------------------------------------

    @Test
    void switchesANodeWithChildren() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        model.decompose(goal, node(NodeKind.GOAL, "child"));

        List<Action> result = mutating(new SwitchDecompositionTypeHandler())
                .executeAction(new SwitchDecompositionTypeOperation(goal.getId(), DecompositionType.OR));

        assertModelUpdate(result);
        assertEquals(DecompositionType.OR, goal.getDecompositionType());
    }

    @Test
    void aLeafHasNothingToSwitch() {
        TreeNode<?> leaf = node(NodeKind.GOAL, "leaf");

        List<Action> result = mutating(new SwitchDecompositionTypeHandler())
                .executeAction(new SwitchDecompositionTypeOperation(leaf.getId(), DecompositionType.AND));

        assertTrue(result.isEmpty());
        assertEquals(DecompositionType.NONE, leaf.getDecompositionType());
        assertEquals(List.of("POPP: Only a node with children has a decomposition to switch"), errors());
    }

    @Test
    void noneIsNotAChoice() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        model.decompose(goal, node(NodeKind.GOAL, "child"));

        List<Action> result = mutating(new SwitchDecompositionTypeHandler())
                .executeAction(new SwitchDecompositionTypeOperation(goal.getId(), DecompositionType.NONE));

        assertTrue(result.isEmpty());
        assertEquals(DecompositionType.AND, goal.getDecompositionType());
        assertTrue(errors().isEmpty());
    }

    @Test
    void anUnknownNodeIsReported() {
        mutating(new SwitchDecompositionTypeHandler())
                .executeAction(new SwitchDecompositionTypeOperation("missing", DecompositionType.OR));
        assertEquals(List.of("POPP: No such node: missing"), errors());
    }

    // -- EdgeCreationHandler and POPPEdgeCreationChecker ----------------------------------------------------------

    private EdgeCreationHandler handler() {
        EdgeCreationHandler handler = new EdgeCreationHandler();
        inject(handler, "context", session.context);
        inject(handler, "actionDispatcher", session.dispatcher);
        return handler;
    }

    private void connect(String sourceElementId, String targetElementId) {
        handler().executeCreation(new CreateEdgeOperation(POPPGModelTypes.CONNECT, sourceElementId, targetElementId));
    }

    @Test
    void connectingTwoNodesOfOneKindDecomposes() {
        TreeNode<?> parent = node(NodeKind.GOAL, "parent");
        TreeNode<?> child = node(NodeKind.GOAL, "child");

        connect(parent.getId(), child.getId());

        assertEquals(parent, child.getParent().orElseThrow());
    }

    @Test
    void connectingRelatedKindsCreatesTheRelation() {
        TreeNode<?> problem = node(NodeKind.PROBLEM, "problem");
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");

        connect(goal.getId(), problem.getId());

        Relation relation = model.getRelations().all().iterator().next();
        assertEquals(RelationType.INVERTS, relation.type());
        assertEquals(problem, relation.source());
    }

    @Test
    void aConnectionMayStartOnADescriptionLabelOrAPort() {
        TreeNode<?> parent = node(NodeKind.GOAL, "parent");
        TreeNode<?> child = node(NodeKind.GOAL, "child");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");

        connect(ids.toPortId(parent), child.getId());
        connect(ids.toDescriptionId(parent), criterion.getId());

        assertEquals(parent, child.getParent().orElseThrow());
        assertEquals(1, model.getRelations().all().size());
    }

    @Test
    void kindsWithoutARelationAreReported() {
        connect(node(NodeKind.CONSEQUENCE, "c").getId(), node(NodeKind.SOLUTION, "s").getId());
        assertTrue(model.getRelations().all().isEmpty());
        assertEquals(List.of("There is no relationship defined between the two node types."), errors());
    }

    @Test
    void aCycleIsReported() {
        TreeNode<?> root = node(NodeKind.GOAL, "root");
        TreeNode<?> child = node(NodeKind.GOAL, "child");
        TreeNode<?> grandchild = node(NodeKind.GOAL, "grandchild");
        model.decompose(root, child);
        model.decompose(child, grandchild);

        connect(grandchild.getId(), root.getId());

        assertTrue(root.getParent().isEmpty());
        assertEquals(1, errors().size());
    }

    @Test
    void connectingANodeToItselfOrToNothingDoesNothing() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        connect(goal.getId(), goal.getId());
        connect(goal.getId(), "missing");
        assertTrue(goal.getChildren().isEmpty());
        assertTrue(errors().isEmpty());
    }

    @Test
    void theCheckerAcceptsExactlyWhatTheHandlerCanConnect() {
        POPPEdgeCreationChecker checker = new POPPEdgeCreationChecker(session.context);
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> otherGoal = node(NodeKind.GOAL, "other");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        TreeNode<?> solution = node(NodeKind.SOLUTION, "solution");
        GModelElement goalLabel = gChild(goal, POPPGModelTypes.NODE_DESCRIPTION);

        assertTrue(checker.isValidSource(POPPGModelTypes.CONNECT, gnode(goal)));
        assertTrue(checker.isValidSource(POPPGModelTypes.CONNECT, goalLabel));
        assertTrue(checker.isValidTarget(POPPGModelTypes.CONNECT, gnode(goal), gnode(otherGoal)));
        assertTrue(checker.isValidTarget(POPPGModelTypes.CONNECT, goalLabel, gnode(criterion)));
        assertFalse(checker.isValidTarget(POPPGModelTypes.CONNECT, gnode(goal), gnode(goal)));
        assertTrue(checker.isValidTarget(POPPGModelTypes.CONNECT, gnode(goal), gnode(solution)), "a solution realizes a goal");
        assertFalse(checker.isValidTarget(POPPGModelTypes.CONNECT, gnode(criterion), gnode(solution)));
    }

    private GModelElement gnode(TreeNode<?> node) {
        return session.gmodelElement(node.getId()).orElseThrow();
    }

    private GModelElement gChild(TreeNode<?> node, String type) {
        return gnode(node).getChildren().stream().filter(c -> c.getType().equals(type)).findFirst().orElseThrow();
    }

    // -- read-only handlers -----------------------------------------------------------------------------------------

    @Test
    void answersTheRequestWithTheTrace() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        model.relate(goal, criterion);
        RequestInspectionAction request = new RequestInspectionAction();
        request.setNodeId(goal.getId());
        request.setMode("PLANNING");
        inject(request, "requestId", "r1"); // set by the client in GLSP

        List<Action> result = reading(new InspectionActionHandler()).executeAction(request);

        InspectionResultAction response = assertInstanceOf(InspectionResultAction.class, result.getFirst());
        assertEquals("r1", response.getResponseId());
        assertEquals("COVERED", response.getResult().coverage());
        assertEquals(List.of(goal.getId(), criterion.getId()), response.getResult().pathNodeIds());
    }

    @Test
    void anUnknownNodeIsUnknownAndAnInvalidModeFallsBackToPlanning() {
        RequestInspectionAction request = new RequestInspectionAction();
        request.setNodeId("missing");
        request.setMode("SOMETHING");

        InspectionResult result = ((InspectionResultAction) reading(new InspectionActionHandler())
                .executeAction(request).getFirst()).getResult();

        assertEquals("UNKNOWN", result.coverage());
        assertEquals("PLANNING", result.mode());
    }

    @Test
    void answersWithTheReportForTheMode() {
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        RequestCoverageReportAction request = new RequestCoverageReportAction();
        request.setMode("VERIFY");
        inject(request, "requestId", "r2"); // set by the client in GLSP

        CoverageReportAction response = (CoverageReportAction) reading(new CoverageReportHandler())
                .executeAction(request).getFirst();

        assertEquals("r2", response.getResponseId());
        assertEquals("VERIFY", response.getReport().mode());
        assertEquals("UNCOVERED", response.getReport().roots().stream()
                .filter(r -> r.id().equals(criterion.getId())).findFirst().orElseThrow().coverage());
    }

    @Test
    void withoutAModelTheAnswerIsAnEmptyReport() {
        POPPTestSession empty = POPPTestSession.withoutModel();
        CoverageReportHandler handler = new CoverageReportHandler();
        inject(handler, "context", empty.context);
        RequestCoverageReportAction request = new RequestCoverageReportAction();
        request.setMode("PLANNING");

        CoverageReportAction response = (CoverageReportAction) handler.executeAction(request).getFirst();

        assertTrue(response.getReport().roots().isEmpty());
        assertTrue(response.getReport().trees().isEmpty());
    }

    @Test
    void listsUncoveredCoverableNodesWithRootCausesAndRemedies() {
        TreeNode<?> goal = node(NodeKind.GOAL, "Ship v2");
        TreeNode<?> child = node(NodeKind.GOAL, "child");
        model.decompose(goal, child);
        node(NodeKind.CONSEQUENCE, "never covered, never listed");
        RequestCoverageGapsAction request = new RequestCoverageGapsAction();
        request.setMode("PLANNING");
        inject(request, "requestId", "r3"); // set by the client in GLSP

        CoverageGapsAction response = (CoverageGapsAction) reading(new CoverageGapsHandler())
                .executeAction(request).getFirst();

        assertEquals("r3", response.getResponseId());
        assertEquals("PLANNING", response.getMode());
        assertEquals(Set.of(goal.getId(), child.getId()),
                Set.copyOf(response.getGaps().stream().map(CoverageGapsAction.Gap::nodeId).toList()));
        CoverageGapsAction.Gap parentGap = response.getGaps().stream()
                .filter(g -> g.nodeId().equals(goal.getId())).findFirst().orElseThrow();
        assertFalse(parentGap.rootCause(), "its child is the place to act");
        assertEquals("", parentGap.message());
        assertEquals("goal \"Ship v2\"", parentGap.label());
        CoverageGapsAction.Gap leafGap = response.getGaps().stream()
                .filter(g -> g.nodeId().equals(child.getId())).findFirst().orElseThrow();
        assertTrue(leafGap.rootCause());
        assertEquals("UNCOVERED", leafGap.coverage());
        assertEquals("needs a success criteria (causes)", leafGap.message());
    }

    @Test
    void aQuestionModeOnlyReportsGapsOfTheKindsItIsAbout() {
        TreeNode<?> problem = node(NodeKind.PROBLEM, "problem");
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        model.relate(problem, goal);
        TreeNode<?> other = node(NodeKind.PROBLEM, "other");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        node(NodeKind.SUCCESS_PROOF, "proof"); // an emitter: never a gap
        RequestCoverageGapsAction request = new RequestCoverageGapsAction();

        request.setMode("PROBLEMS_BY_GOALS");
        CoverageGapsAction byGoals = (CoverageGapsAction) reading(new CoverageGapsHandler()).executeAction(request).getFirst();
        assertEquals("PROBLEMS_BY_GOALS", byGoals.getMode());
        assertEquals(List.of(other.getId()), byGoals.getGaps().stream().map(CoverageGapsAction.Gap::nodeId).toList(),
                "the goal is covered by itself, the criterion is not asked about");

        request.setMode("PROBLEMS_BY_PROOFS");
        CoverageGapsAction byProofs = (CoverageGapsAction) reading(new CoverageGapsHandler()).executeAction(request).getFirst();
        assertEquals(Set.of(problem.getId(), other.getId(), goal.getId(), criterion.getId()),
                Set.copyOf(byProofs.getGaps().stream().map(CoverageGapsAction.Gap::nodeId).toList()),
                "problems, the goal and the criterion lie between proofs and problems");
    }

    @Test
    void triggersARunWhoseResultIsPushedLater() {
        node(NodeKind.GOAL, "first");
        node(NodeKind.GOAL, "second");
        ValidateModelHandler handler = new ValidateModelHandler();
        inject(handler, "validationService", session.validationService);

        assertTrue(handler.executeAction(new ValidateModelAction()).isEmpty());

        ValidationResultAction result = session.dispatcher.await(ValidationResultAction.class,
                a -> a.getReport().modelVersion() == model.getVersion(), 5000).orElseThrow();
        assertTrue(result.getReport().valid(), "two trees of a kind are only a warning");
        assertEquals("MULTIPLE_TREES", result.getReport().issues().getFirst().code());
    }
}
