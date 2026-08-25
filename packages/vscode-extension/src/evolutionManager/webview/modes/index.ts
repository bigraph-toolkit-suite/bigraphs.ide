import type { RewritingMode } from './RewritingMode.js';
import { evolutionMode } from './evolutionMode.js';

/**
 * Registry of all rewriting modes shown as tabs in the Rewriting view.
 *
 * <p>Add a new mode by appending it to the list — the tab bar, payload
 * assembly and run-finished dispatch all flow through this registry. Evolution
 * comes first so it is the initially active tab.</p>
 */
const REWRITING_MODES: ReadonlyArray<RewritingMode> = Object.freeze([
	evolutionMode,
]);

/** All registered rewriting modes, in tab order. */
export function getRewritingModes(): ReadonlyArray<RewritingMode> {
	return REWRITING_MODES;
}
