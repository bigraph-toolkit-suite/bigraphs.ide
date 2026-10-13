package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.ValidateModelAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.ValidationResultAction;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates the model in the background after every structural change, so that editing never waits for it.
 *
 * <ol>
 *   <li>Every structural {@link POPPEvent} (re)starts a short debounce timer: one user operation emits several events,
 *       and a run in between would see a half-applied operation.</li>
 *   <li>When the timer fires, it dispatches a {@link ValidateModelAction} to the session's own dispatcher. Actions are
 *       handled one after another on the dispatch thread, so {@link #runNow()} takes its snapshot between operations,
 *       never during one.</li>
 *   <li>The matching runs on a background thread against the snapshot; the result is dispatched as a
 *       {@link ValidationResultAction}. Runs that were overtaken by a newer one are dropped.</li>
 * </ol>
 */
@Singleton
public class POPPValidationService implements POPPEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(POPPValidationService.class);
    static final long DEBOUNCE_MS = 200;

    /** One daemon thread for all sessions: validation is short and never urgent. */
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "popp-validation");
        t.setDaemon(true);
        return t;
    });

    private final POPPExtensionContext context;
    // Providers, not instances: GLSP creates all action handlers (and with them this service) when the session
    // starts, before a model is loaded. The synchronizer reads the model state's bigraph in its constructor, so it
    // must only be created on first use, which is after loading. The dispatcher is the one under construction then.
    private final Provider<POPPBigraphSynchronizer> bigraphSynchronizer;
    private final Provider<ActionDispatcher> actionDispatcher;
    private final POPPModelValidator validator = new POPPModelValidator();
    private final AtomicLong generation = new AtomicLong();
    private ScheduledFuture<?> pending;
    private volatile ValidationReport last;

    @Inject
    public POPPValidationService(final POPPExtensionContext context,
                                 final Provider<POPPBigraphSynchronizer> bigraphSynchronizer,
                                 final Provider<ActionDispatcher> actionDispatcher) {
        this.context = context;
        this.bigraphSynchronizer = bigraphSynchronizer;
        this.actionDispatcher = actionDispatcher;
    }

    @Override
    public void onPOPPEvent(final POPPEvent event) {
        if (event instanceof POPPEvent.NodeMoved || event instanceof POPPEvent.NodeDescriptionChanged) {
            return; // neither changes the structure
        }
        schedule();
    }

    /** (Re)starts the debounce timer. */
    public synchronized void schedule() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = EXECUTOR.schedule(() -> actionDispatcher.get().dispatch(new ValidateModelAction()),
                DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Snapshot now, match in the background. Must be called on the dispatch thread (see the class comment). */
    public void runNow() {
        POPPModel model = context.getOwnState().getPoppModel();
        if (model == null) {
            return; // nothing loaded (yet), e.g. a plain bigraph diagram
        }
        var bigraph = bigraphSynchronizer.get().getBigraph();
        if (bigraph == null) {
            return;
        }
        POPPModelValidator.Snapshot snapshot = POPPModelValidator.snapshot(bigraph, model.getVersion());
        long run = generation.incrementAndGet();
        EXECUTOR.execute(() -> {
            if (run != generation.get()) {
                return; // a newer snapshot is already queued
            }
            try {
                ValidationReport report = validator.validate(snapshot);
                if (run != generation.get()) {
                    return;
                }
                last = report;
                LOGGER.debug("POPP validation: {} errors, {} warnings, {} patterns matched, {} skipped, {} ms",
                        report.errors(), report.warnings(), report.patternsMatched(), report.patternsSkipped(),
                        report.durationMs());
                actionDispatcher.get().dispatch(new ValidationResultAction(report));
            } catch (RuntimeException e) {
                LOGGER.warn("POPP validation failed", e);
            }
        });
    }

    /** The most recent report, or {@code null} before the first run. */
    public ValidationReport lastReport() {
        return last;
    }
}
