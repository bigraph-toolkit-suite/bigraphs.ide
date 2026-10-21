package org.eclipse.glsp.example.bigraph.extension.popp.types;

/**
 * Reacts to domain-model changes: the bigraph and GModel synchronizers and the validation service. Also used within
 * the domain model, where nodes forward their children's events up to the
 * {@link org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel}.
 */
@FunctionalInterface
public interface POPPEventListener  {
    void onPOPPEvent(final POPPEvent event);
}
