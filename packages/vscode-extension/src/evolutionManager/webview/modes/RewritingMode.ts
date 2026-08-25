import type { EvoState } from '../EvoState.js';
import type { RewriteRule, VerificationBigraph, VsCodeApi } from '../types.js';
import { RuleApplicationStrategy } from '../types.js';

/**
 * Declarative UI configuration of a rewriting mode. The shared form applies
 * these settings when the mode becomes active instead of each mode toggling
 * DOM elements by hand.
 */
export interface RewritingModeUi {
	/** Label above the verification/goal list (e.g. "Verification" or "Goals"). */
	verificationLabel: string;
	/** Text of the toggle tag on each verification entry (e.g. "stop" or "goal"). */
	verificationTagText: string;
	/** Display labels for the shared strategy wire values. */
	strategyLabels: Record<RuleApplicationStrategy, string>;
	strategyTitle: string;
	showVerification: boolean;
	showStrategy: boolean;
	showMaxOperations: boolean;
	showCheckpointFileGeneration: boolean;
	/** Whether the shared rewrite-rule list is shown (a mode may render its own). */
	showRuleList: boolean;
	/** Whether each rewrite rule shows its per-rule play button. */
	perRulePlay: boolean;
	/** Whether each rewrite rule shows the "redex → reactum" subtitle. */
	showRuleSubtitle: boolean;
}

/**
 * Services the mode controller hands to a {@link RewritingMode}. Modes mount
 * their private fields into the provided containers and use the callbacks to
 * interact with the shared UI without owning it.
 */
export interface RewritingModeContext {
	readonly state: EvoState;
	readonly vscode: VsCodeApi;
	/** Mount point directly below the tab bar for mode-specific fields. */
	readonly headerEl: HTMLElement;
	/** Mount point at the end of the form for mode-specific rows. */
	readonly formExtrasEl: HTMLElement;
	/** Re-renders the shared lists and re-applies the mode UI (incl. play gating). */
	requestRender(): void;
	switchMode(modeId: string): void;
	hasMode(modeId: string): boolean;
}

/**
 * A tab in the Rewriting view. Evolution is the built-in default; further
 * modes (e.g. Exploration, Operation) are contributed through the registry in
 * `modes/index.ts` — the mode counterpart of the extension registries on the
 * server, host and GLSP-webview side.
 */
export interface RewritingMode {
	/** Stable id, also used as the key in EvolutionRunAction.extensionOptions. */
	readonly id: string;
	/** Tab label. */
	readonly label: string;
	readonly ui: RewritingModeUi;

	onActivate?(ctx: RewritingModeContext): void;
	onDeactivate?(ctx: RewritingModeContext): void;
	/** Called after an evolution.json has been loaded into `state.extensionJson`. */
	onDocumentLoaded?(ctx: RewritingModeContext): void;

	/** This mode's contribution to `extensionOptions` when Play is pressed. */
	collectExtensionOptions?(ctx: RewritingModeContext): Record<string, unknown>;
	/** Last-chance hook to adjust the outgoing play payload (e.g. jsonRootValues). */
	contributePayload?(payload: Record<string, unknown>, ctx: RewritingModeContext): void;
	/** When false, the shared Play button is disabled (e.g. no path selected yet). */
	canPlay?(ctx: RewritingModeContext): boolean;
	/** Called with the run's extensionResults (or null) when an operation finishes. */
	onRunFinished?(results: Record<string, unknown> | null, ctx: RewritingModeContext): void;

	/** Extra HTML appended to each rewrite-rule item (e.g. a route textbox). */
	renderRuleExtra?(rule: RewriteRule, index: number, ctx: RewritingModeContext): string;
	/** Event binding for {@link renderRuleExtra} output, called after each render. */
	bindRuleExtras?(listEl: HTMLElement, ctx: RewritingModeContext): void;

	/** Extra HTML appended to each verification/goal item (e.g. path badges). */
	renderVerificationExtra?(vb: VerificationBigraph, index: number, ctx: RewritingModeContext): string;
	/** Event binding for {@link renderVerificationExtra} output. */
	bindVerificationExtras?(listEl: HTMLElement, ctx: RewritingModeContext): void;
}
