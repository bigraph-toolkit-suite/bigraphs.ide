import type { EvoState } from './EvoState.js';
import type { VsCodeApi } from './types.js';
import type { ModeController } from './modes/ModeController.js';
import {
	RuleApplicationStrategy,
	normalizeRuleApplicationStrategy,
} from '../ruleApplicationStrategy.js';

export class EvoForm {
	private readonly state: EvoState;
	private readonly vscode: VsCodeApi;
	private readonly modes: ModeController;

	// DOM references
	private readonly placeholderEl: HTMLElement;
	private readonly formEl: HTMLElement;
	private readonly configPathBarEl: HTMLElement;
	private readonly actionBarEl: HTMLElement;
	private readonly modeTabsEl: HTMLElement;
	private readonly btnPlay: HTMLButtonElement;
	private readonly btnPause: HTMLButtonElement;
	private readonly evolutionLabelEl: HTMLInputElement;
	private readonly btnCloseProject: HTMLButtonElement;
	private readonly maxOpsEnabledEl: HTMLInputElement;
	private readonly maxOpsEl: HTMLInputElement;
	private readonly checkpointFileGenEl: HTMLInputElement;
	private readonly ruleApplicationStrategyEl: HTMLSelectElement;
	private readonly strategyLabelEl: HTMLLabelElement;
	private readonly verificationSectionLabelEl: HTMLElement;
	private readonly verificationFieldEl: HTMLElement;
	private readonly rulesFieldEl: HTMLElement;
	private readonly strategyFieldEl: HTMLElement;
	private readonly maxOpsFieldEl: HTMLElement;
	private readonly checkpointFileGenRowEl: HTMLElement;

	private running = false;

	constructor(state: EvoState, vscode: VsCodeApi, modes: ModeController) {
		this.state  = state;
		this.vscode = vscode;
		this.modes  = modes;

		this.placeholderEl      = document.getElementById('placeholder')!;
		this.formEl             = document.getElementById('form')!;
		this.configPathBarEl    = document.getElementById('config-path-bar')!;
		this.actionBarEl        = document.getElementById('action-bar')!;
		this.modeTabsEl         = document.getElementById('mode-tabs')!;
		this.btnPlay            = document.getElementById('btnPlay')  as HTMLButtonElement;
		this.btnPause           = document.getElementById('btnPause') as HTMLButtonElement;
		this.evolutionLabelEl   = document.getElementById('evolutionLabel')           as HTMLInputElement;
		this.btnCloseProject    = document.getElementById('btnCloseProject')         as HTMLButtonElement;
		this.maxOpsEnabledEl    = document.getElementById('maxOperationsEnabled')     as HTMLInputElement;
		this.maxOpsEl           = document.getElementById('maxOperations')            as HTMLInputElement;
		this.checkpointFileGenEl = document.getElementById('checkpointFileGeneration') as HTMLInputElement;
		this.ruleApplicationStrategyEl = document.getElementById('ruleApplicationStrategy') as HTMLSelectElement;
		this.strategyLabelEl = document.querySelector('label[for="ruleApplicationStrategy"]') as HTMLLabelElement;
		this.verificationSectionLabelEl = document.getElementById('verificationSectionLabel')!;
		this.verificationFieldEl   = document.getElementById('verificationField')!;
		this.rulesFieldEl          = document.getElementById('rulesField')!;
		this.strategyFieldEl       = document.getElementById('strategyField')!;
		this.maxOpsFieldEl         = document.getElementById('maxOpsField')!;
		this.checkpointFileGenRowEl = document.getElementById('checkpointFileGenRow')!;

		this.refreshStrategyOptions();
		this.setActionBarVisible(false);
		this.setRunningState(false);
		this.applyMaxOpsEnabledState();
		this.initListeners();
	}

	// ── Config path bar ──────────────────────────────────────────────────────

	setConfigPathBar(relPath: string | null): void {
		if (relPath) {
			this.configPathBarEl.textContent = relPath;
			this.configPathBarEl.title       = relPath;
			this.configPathBarEl.classList.add('visible');
		} else {
			this.configPathBarEl.textContent = '';
			this.configPathBarEl.title       = '';
			this.configPathBarEl.classList.remove('visible');
		}
	}

	// ── Action bar ────────────────────────────────────────────────────────────

	isRunning(): boolean {
		return this.running;
	}

	setRunningState(running: boolean): void {
		this.running = running;
		this.btnPause.disabled = !running;
		this.btnPlay.style.display  = running ? 'none' : '';
		this.btnPause.style.display = running ? ''     : 'none';
		this.refreshPlayEnabled();
	}

	/** Applies mode gating on top of the running state (e.g. no path selected yet). */
	refreshPlayEnabled(): void {
		this.btnPlay.disabled = this.running || !this.modes.canPlay();
	}

	setActionBarVisible(visible: boolean): void {
		this.actionBarEl.style.display = visible ? '' : 'none';
	}

	applyMaxOpsEnabledState(): void {
		this.maxOpsEl.disabled = !this.maxOpsEnabledEl.checked;
	}

	// ── Mode UI ────────────────────────────────────────────────────────────────

	/** Applies the active mode's declarative UI settings to the shared form. */
	applyModeUi(): void {
		const ui = this.modes.ui;
		this.verificationSectionLabelEl.textContent = ui.verificationLabel;
		this.verificationFieldEl.style.display    = ui.showVerification ? '' : 'none';
		this.rulesFieldEl.style.display           = ui.showRuleList ? '' : 'none';
		this.strategyFieldEl.style.display        = ui.showStrategy ? '' : 'none';
		this.maxOpsFieldEl.style.display          = ui.showMaxOperations ? '' : 'none';
		this.checkpointFileGenRowEl.style.display = ui.showCheckpointFileGeneration ? '' : 'none';
		this.refreshStrategyOptions();
		this.refreshPlayEnabled();
	}

	/** Rebuilds the strategy dropdown with the active mode's labels. */
	refreshStrategyOptions(): void {
		const previous = normalizeRuleApplicationStrategy(this.ruleApplicationStrategyEl.value);
		const ui = this.modes.ui;
		this.ruleApplicationStrategyEl.replaceChildren();
		for (const strategy of [RuleApplicationStrategy.FirstFirst, RuleApplicationStrategy.RoundRobin]) {
			const opt = document.createElement('option');
			opt.value = strategy;
			opt.textContent = ui.strategyLabels[strategy];
			this.ruleApplicationStrategyEl.appendChild(opt);
		}
		this.ruleApplicationStrategyEl.value = previous;
		this.ruleApplicationStrategyEl.title = ui.strategyTitle;
		if (this.strategyLabelEl) {
			this.strategyLabelEl.textContent = 'Strategy';
		}
	}

	// ── Form show/hide ────────────────────────────────────────────────────────

	showEvolutionForm(visible: boolean): void {
		if (visible) {
			this.placeholderEl.textContent   = '';
			this.placeholderEl.style.display = 'none';
			this.formEl.classList.add('visible');
			this.modeTabsEl.classList.add('visible');
			this.setActionBarVisible(true);
		} else {
			this.placeholderEl.textContent   = 'No evolution selected. Click an entry in the Evolutions list above.';
			this.placeholderEl.style.display = '';
			this.formEl.classList.remove('visible');
			this.modeTabsEl.classList.remove('visible');
			this.setActionBarVisible(false);
			this.state.currentWorkspaceBigraph = '';
		}
	}

	// ── State snapshot ────────────────────────────────────────────────────────

	reportFormState(): void {
		const st = this.state;
		this.vscode.postMessage({
			type:  'formStateChanged',
			state: {
				evolutionLabel:             this.evolutionLabelEl.value.trim(),
				bigraphFsPath:              st.activeFsPath ?? '',
				bigraphLockedFromConfig:    st.bigraphLockedFromConfig,
				rewriteRules:               st.lastRewriteRules,
				verificationBigraphs:       st.lastVerificationBigraphs,
				maxOperationsEnabled:       this.maxOpsEnabledEl.checked,
				maxOperations:              parseInt(this.maxOpsEl.value, 10) || 10,
				checkpointFileGeneration:   this.checkpointFileGenEl.checked,
				ruleApplicationStrategy:    this.ruleApplicationStrategyEl.value as RuleApplicationStrategy,
				workspaceBigraph:           st.currentWorkspaceBigraph,
			},
		});
	}

	collectPayload(actionType: string): Record<string, unknown> {
		const st = this.state;
		const payload: Record<string, unknown> = {
			type:                       'evolutionAction',
			actionType,
			clientId:                   st.activeClientId,
			evolutionLabel:             this.evolutionLabelEl.value.trim(),
			bigraphPath:                st.activeFsPath ?? (document.getElementById('bigraphPath') as HTMLInputElement | null)?.value.trim() ?? '',
			rewriteRules:               st.lastRewriteRules,
			verificationBigraphs:       st.lastVerificationBigraphs,
			maxOperationsEnabled:       this.maxOpsEnabledEl.checked,
			maxOperations:              parseInt(this.maxOpsEl.value, 10) || 10,
			checkpointFileGeneration:   this.checkpointFileGenEl.checked,
			ruleApplicationStrategy:    this.ruleApplicationStrategyEl.value as RuleApplicationStrategy,
			modeId:                     this.modes.activeMode.id,
			extensionOptions:           this.modes.collectExtensionOptions(),
		};
		this.modes.contributePayload(payload);
		return payload;
	}

	/** Runs a single rewrite rule once from the current evolution cursor. */
	collectPlayRulePayload(ruleId: string): Record<string, unknown> {
		return {
			...this.collectPayload('play'),
			targetRuleId: ruleId,
			maxOperationsEnabled: true,
			maxOperations: 1,
		};
	}

	// ── Field value setters (called by message handlers) ─────────────────────

	setEvolutionLabel(value: string): void    { this.evolutionLabelEl.value        = value; }
	setMaxOpsEnabled(value: boolean): void    { this.maxOpsEnabledEl.checked       = value; this.applyMaxOpsEnabledState(); }
	setMaxOps(value: number): void            { this.maxOpsEl.value                = String(value); }
	setCheckpointFileGen(value: boolean): void { this.checkpointFileGenEl.checked  = value; }
	setRuleApplicationStrategy(value: string): void {
		this.ruleApplicationStrategyEl.value = normalizeRuleApplicationStrategy(value);
	}

	// ── Private ───────────────────────────────────────────────────────────────

	private initListeners(): void {
		this.btnPlay.addEventListener('click', () => {
			if (this.btnPlay.disabled) { return; }
			this.vscode.postMessage(this.collectPayload('play'));
		});
		this.btnPause.addEventListener('click', () => {
			this.vscode.postMessage({
				type:        'evolutionAction',
				actionType:  'pause',
				clientId:    this.state.activeClientId,
				operationId: this.state.currentOperationId,
			});
		});

		this.evolutionLabelEl.addEventListener('input', () => this.reportFormState());
		this.btnCloseProject.addEventListener('click', () => {
			this.vscode.postMessage({ type: 'closeEvolutionProject' });
		});
		this.maxOpsEnabledEl.addEventListener('change', () => {
			this.applyMaxOpsEnabledState();
			this.reportFormState();
		});
		this.maxOpsEl.addEventListener('input', () => this.reportFormState());
		this.checkpointFileGenEl.addEventListener('change',  () => this.reportFormState());
		this.ruleApplicationStrategyEl.addEventListener('change', () => this.reportFormState());
	}
}
