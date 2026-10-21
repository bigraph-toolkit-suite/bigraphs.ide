package org.eclipse.glsp.example.bigraph.extension.popp;

import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.sync.POPPBigraphLoader;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.sync.POPPBigraphSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.sync.POPPGModelSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.handler.POPPValidationService;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateRepository;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;

/**
 * A POPP editor session without GLSP's runtime: a real {@link BigraphModelState} for a POPP file, the extension
 * context and state, both synchronizers, the validation service and the loader, wired as {@code POPPExtension} and
 * Guice wire them. {@link #model()} is the loaded model; editing it updates the GModel and the bigraph.
 */
public final class POPPTestSession {
    public final PureBigraphMutable bigraph;
    public final BigraphModelState modelState;
    public final POPPExtensionContext context;
    public final POPPGModelSynchronizer gmodelSynchronizer;
    public final POPPBigraphSynchronizer bigraphSynchronizer;
    public final POPPValidationService validationService;
    public final Dispatcher dispatcher = new Dispatcher();

    /** A new, empty POPP file. */
    public POPPTestSession() {
        this(BigraphTestSupport.emptyBigraph(POPPBigraphSignature.create()), true);
    }

    private POPPTestSession(PureBigraphMutable bigraph, boolean load) {
        this.bigraph = bigraph;
        BigraphMetaInformation meta = new BigraphMetaInformation();
        meta.setModelType(POPPExtension.POPP_VARIANT_ID);
        this.modelState = BigraphTestSupport.initializedState(bigraph, meta);
        this.context = new POPPExtensionContext(modelState, new ExtensionStateRepository(modelState));
        this.gmodelSynchronizer = new POPPGModelSynchronizer(context);
        this.bigraphSynchronizer = new POPPBigraphSynchronizer(context);
        this.validationService = new POPPValidationService(context, () -> bigraphSynchronizer, () -> dispatcher);
        if (load) {
            state().setInitialLoadedMeta(Map.of()); // readExtensionMeta runs before loading
            new POPPBigraphLoader(context, gmodelSynchronizer, bigraphSynchronizer).loadModelFromBigraph(bigraph);
            validationService.follow(context.getOwnState().getPoppModel());
        }
    }

    /** Opens {@code bigraph} (e.g. another session's) in a new session, as opening a file would. */
    public static POPPTestSession open(PureBigraphMutable bigraph) {
        return new POPPTestSession(bigraph, true);
    }

    /** A session whose file is open but whose POPP model was never loaded. */
    public static POPPTestSession withoutModel() {
        return new POPPTestSession(BigraphTestSupport.emptyBigraph(POPPBigraphSignature.create()), false);
    }

    public POPPExtensionState state() {
        return context.getOwnState();
    }

    public POPPModel model() {
        return state().getPoppModel();
    }

    public POPPBigraph popp() {
        return state().getPoppBigraph();
    }

    /** The POPP variant's GModel root, which the GModel synchronizer fills. */
    public GModelRoot gmodelRoot() {
        return state().getGModel().getRoot();
    }

    public Optional<GModelElement> gmodelElement(String id) {
        return gmodelRoot().getChildren().stream().filter(e -> id.equals(e.getId())).findFirst();
    }

    public VariantGate variantGate() {
        return BigraphTestSupport.variantGateFor(modelState);
    }

    /** Thread-safe: the validation service dispatches from its background thread. */
    public static final class Dispatcher implements ActionDispatcher {
        private final List<Action> dispatched = new ArrayList<>();
        private final List<Action> afterNextUpdate = new ArrayList<>();

        @Override
        public synchronized CompletableFuture<Void> dispatch(Action action) {
            dispatched.add(action);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized void dispatchAfterNextUpdate(Action... actions) {
            afterNextUpdate.addAll(Arrays.asList(actions));
        }

        public synchronized <T extends Action> List<T> dispatched(Class<T> type) {
            return dispatched.stream().filter(type::isInstance).map(type::cast).toList();
        }

        public synchronized <T extends Action> List<T> afterNextUpdate(Class<T> type) {
            return afterNextUpdate.stream().filter(type::isInstance).map(type::cast).toList();
        }

        public synchronized void clear() {
            dispatched.clear();
            afterNextUpdate.clear();
        }

        /** Waits up to {@code timeoutMs} for a dispatched action of {@code type} that satisfies {@code condition}. */
        public <T extends Action> Optional<T> await(Class<T> type, Predicate<T> condition, long timeoutMs) {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                Optional<T> found = dispatched(type).stream().filter(condition).findFirst();
                if (found.isPresent() || System.currentTimeMillis() > end) return found;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }

        @Override
        public void dispose() {}

        @Override
        public boolean isDisposed() {
            return false;
        }
    }
}
