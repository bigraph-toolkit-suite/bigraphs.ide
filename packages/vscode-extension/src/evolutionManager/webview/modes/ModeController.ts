import type { EvoState } from '../EvoState.js';
import type { VsCodeApi } from '../types.js';
import type { RewritingMode, RewritingModeContext, RewritingModeUi } from './RewritingMode.js';

/**
 * Owns the tab bar and the active {@link RewritingMode}. The shared UI
 * components (form, rule list, verification list) consult the controller for
 * the active mode's declarative UI settings and hooks; the controller never
 * reaches into their DOM directly — it triggers a re-render callback wired
 * up in `index.ts` instead (mediator pattern).
 */
export class ModeController {
	private readonly modes: ReadonlyArray<RewritingMode>;
	private readonly context: RewritingModeContext;
	private active: RewritingMode;

	private readonly tabBarEl: HTMLElement;
	private readonly headerEl: HTMLElement;
	private readonly formExtrasEl: HTMLElement;

	/** Set by index.ts: re-renders shared lists and re-applies mode UI. */
	private renderCallback: (() => void) | null = null;

	constructor(modes: ReadonlyArray<RewritingMode>, state: EvoState, vscode: VsCodeApi) {
		if (modes.length === 0) {
			throw new Error('At least one rewriting mode must be registered.');
		}
		this.modes = modes;
		this.active = modes[0];

		this.tabBarEl     = document.getElementById('mode-tabs')!;
		this.headerEl     = document.getElementById('mode-header')!;
		this.formExtrasEl = document.getElementById('mode-form-extras')!;

		const controller = this;
		this.context = {
			state,
			vscode,
			headerEl: this.headerEl,
			formExtrasEl: this.formExtrasEl,
			requestRender: () => controller.requestRender(),
			switchMode: (modeId: string) => controller.switchTo(modeId),
			hasMode: (modeId: string) => controller.modes.some((m) => m.id === modeId),
		};
	}

	/** Renders the tab bar and activates the first (Evolution) mode. */
	init(onRender: () => void): void {
		this.renderCallback = onRender;
		this.renderTabBar();
		this.active.onActivate?.(this.context);
		this.requestRender();
	}

	get activeMode(): RewritingMode { return this.active; }
	get ui(): RewritingModeUi { return this.active.ui; }
	get ctx(): RewritingModeContext { return this.context; }

	hasMode(modeId: string): boolean {
		return this.modes.some((m) => m.id === modeId);
	}

	switchTo(modeId: string): void {
		const next = this.modes.find((m) => m.id === modeId);
		if (!next || next === this.active) { return; }
		this.active.onDeactivate?.(this.context);
		this.headerEl.replaceChildren();
		this.formExtrasEl.replaceChildren();
		this.active = next;
		this.markActiveTab();
		next.onActivate?.(this.context);
		this.requestRender();
	}

	/** This mode's contribution to `extensionOptions`, keyed by mode id. */
	collectExtensionOptions(): Record<string, unknown> {
		const options = this.active.collectExtensionOptions?.(this.context);
		return options && Object.keys(options).length > 0
			? { [this.active.id]: options }
			: {};
	}

	contributePayload(payload: Record<string, unknown>): void {
		this.active.contributePayload?.(payload, this.context);
	}

	canPlay(): boolean {
		return this.active.canPlay?.(this.context) ?? true;
	}

	notifyRunFinished(results: Record<string, unknown> | null): void {
		this.active.onRunFinished?.(results, this.context);
	}

	notifyDocumentLoaded(): void {
		for (const mode of this.modes) {
			mode.onDocumentLoaded?.(this.context);
		}
	}

	// ── Private ────────────────────────────────────────────────────────────

	private requestRender(): void {
		this.renderCallback?.();
	}

	private renderTabBar(): void {
		this.tabBarEl.replaceChildren();
		for (const mode of this.modes) {
			const tab = document.createElement('button');
			tab.type = 'button';
			tab.className = 'mode-tab';
			tab.dataset.modeId = mode.id;
			tab.textContent = mode.label;
			tab.addEventListener('click', () => this.switchTo(mode.id));
			this.tabBarEl.appendChild(tab);
		}
		this.markActiveTab();
	}

	private markActiveTab(): void {
		this.tabBarEl.querySelectorAll<HTMLElement>('.mode-tab').forEach((tab) => {
			tab.classList.toggle('active', tab.dataset.modeId === this.active.id);
		});
	}
}
