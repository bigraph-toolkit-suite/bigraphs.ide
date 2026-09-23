package org.eclipse.glsp.example.bigraph.extension.popp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extensions.AbstractExtensionContext;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateRepository;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;


@Singleton
public class POPPExtensionContext extends AbstractExtensionContext<POPPExtensionState> {
    @Inject
    public POPPExtensionContext(final IBigraphModelState bigraphModelState,
                                final ExtensionStateRepository repository) {
        super(bigraphModelState, repository, POPPExtension.STATE_KEY, () -> new POPPExtensionState(bigraphModelState));
    }

}
