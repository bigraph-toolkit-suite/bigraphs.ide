package org.eclipse.glsp.example.bigraph.extension.popp.state;

import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateKey;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extensions.AbstractExtensionContext;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateRepository;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;

@Singleton
public class POPPExtensionContext extends AbstractExtensionContext<POPPExtensionState> {
    /** The key of the POPP extension\'s per-diagram state. */
    public static final ExtensionStateKey<POPPExtensionState> STATE_KEY =
            ExtensionStateKey.of("popp", POPPExtensionState.class);

    @Inject
    public POPPExtensionContext(final IBigraphModelState bigraphModelState,
                                final ExtensionStateRepository repository) {
        super(bigraphModelState, repository, STATE_KEY, () -> new POPPExtensionState(bigraphModelState));
    }

}
