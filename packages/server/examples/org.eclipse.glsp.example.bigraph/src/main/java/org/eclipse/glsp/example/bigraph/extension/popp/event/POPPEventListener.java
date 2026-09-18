package org.eclipse.glsp.example.bigraph.extension.popp.event;

/** Implemented by the bigraph- and GModel-side synchronizers to react to domain-model changes. */
public interface POPPEventListener  {

    void onPOPPEvent(final POPPEvent event);
}
