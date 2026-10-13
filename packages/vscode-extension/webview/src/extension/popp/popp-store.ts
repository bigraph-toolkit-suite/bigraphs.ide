import type {
    CoverageGap,
    CoverageReport,
    PoppCoverageMode,
    ValidationReport
} from './popp-actions';

/**
 * Client state shared by the POPP palette, the analysis controller and the sidebar view (through the extension host).
 * One webview shows one diagram, so a module-level store is enough.
 */
export interface PoppViewState {
    /** The POPP palette is mounted, i.e. the POPP variant is the active one. */
    active: boolean;
    mode: PoppCoverageMode;
    validation?: ValidationReport;
    report?: CoverageReport;
    gaps?: CoverageGap[];
    /** Uncovered nodes are highlighted in the diagram. */
    gapsActive: boolean;
}

type Listener = (state: Readonly<PoppViewState>, changed: ReadonlySet<keyof PoppViewState>) => void;

class PoppStore {
    private current: PoppViewState = { active: false, mode: 'PLANNING', gapsActive: false };
    private readonly listeners = new Set<Listener>();

    get state(): Readonly<PoppViewState> {
        return this.current;
    }

    update(patch: Partial<PoppViewState>): void {
        const changed = new Set<keyof PoppViewState>();
        (Object.keys(patch) as (keyof PoppViewState)[]).forEach(key => {
            if (this.current[key] !== patch[key]) {
                changed.add(key);
            }
        });
        if (changed.size === 0) {
            return;
        }
        this.current = { ...this.current, ...patch };
        Array.from(this.listeners).forEach(l => l(this.current, changed));
    }

    /** @returns an unsubscribe function */
    subscribe(listener: Listener): () => void {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    }
}

export const poppStore = new PoppStore();
