import type { EvolutionOperation, RewriteRule, VerificationBigraph } from './types.js';

/** Singleton holding all shared mutable state for the Evolution Manager webview. */
export class EvoState {
	/** clientId of the currently active bigraph editor tab. */
	activeClientId: string | null = null;

	/** Absolute fsPath of the active tab (used in the action payload). */
	activeFsPath: string | null = null;

	/** When true the bigraph path was provided by a loaded evolution config and must not be overwritten by activeTabChanged. */
	bigraphLockedFromConfig = false;

	/** Absolute fsPath of workspace-bigraph.xmi for the currently active evolution. */
	currentWorkspaceBigraph = '';

	/** Last rewrite rules pushed by the provider. */
	lastRewriteRules: RewriteRule[] = [];

	/** Last verification bigraphs pushed by the provider. */
	lastVerificationBigraphs: VerificationBigraph[] = [];

	/** Maps verificationId → true (matched) | false (not matched). Absent key means unchecked. */
	verificationCheckStates: Record<string, boolean> = {};

	/** Global map: operationId → runFolder path. */
	operationMap: Record<string, string> = {};

	/** The operationId of the most recently started (or still running) operation. */
	currentOperationId: string | null = null;

	/** The operationId the cursor is currently pointing at in the evolution tree. */
	currentCursorOperationId: string | null = null;

	/** Most recent file dragged from the Bigraph Explorer (cleared after drop). */
	latestDragged: { fsPath: string; label?: string; relativePath?: string } | null = null;

	/** True while a drag from the Explorer is in-flight and no target has consumed it yet. */
	awaitingDropTarget = false;

	// ── Helpers ──────────────────────────────────────────────────────────────

	static escapeHtml(str: string): string {
		return String(str)
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;');
	}

	/** Returns the latest operation matching the given id, or null. */
	findOperation(ops: EvolutionOperation[], id: string | null): EvolutionOperation | null {
		if (!id) { return null; }
		return ops.find((o) => o.id === id) ?? null;
	}

	/** Clears all verification check states. */
	clearCheckStates(): void {
		this.verificationCheckStates = {};
	}

	/** Sets check states from an operation's verification array. Clears existing states first. */
	applyCheckStatesFromOperation(op: EvolutionOperation | null): void {
		this.clearCheckStates();
		if (op?.verification) {
			for (const entry of op.verification) {
				if (typeof entry.id === 'string') {
					this.verificationCheckStates[entry.id] = entry.state === true;
				}
			}
		}
	}
}
