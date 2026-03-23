package org.eclipse.glsp.example.bigraph.evolution;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EvolutionRegistryTest {

    @AfterEach
    void tearDown() {
        EvolutionRegistry registry = EvolutionRegistry.getInstance();
        String active = registry.getActiveOperationId();
        if (active != null) {
            registry.remove(active);
        }
    }

    @Test
    void registerExclusiveRejectsSecondRunWhileFirstIsActive() {
        EvolutionRegistry registry = EvolutionRegistry.getInstance();

        EvolutionOperation first = new EvolutionOperation("op-1", "play");
        String firstId = registry.registerExclusive(first);
        assertNotNull(firstId);

        EvolutionOperation second = new EvolutionOperation("op-2", "play");
        String secondId = registry.registerExclusive(second);
        assertNull(secondId);

        registry.remove(firstId);
        String afterRemoval = registry.registerExclusive(second);
        assertNotNull(afterRemoval);
        registry.remove(afterRemoval);
    }
}
