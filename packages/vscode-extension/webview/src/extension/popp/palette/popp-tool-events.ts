type Listener = () => void;

const listeners = new Set<Listener>();

export const poppToolEvents = {
    /** @returns an unsubscribe function */
    onDefaultToolsEnabled(listener: Listener): () => void {
        listeners.add(listener);
        return () => listeners.delete(listener);
    },
    emitDefaultToolsEnabled(): void {
        Array.from(listeners).forEach(l => l());
    }
};