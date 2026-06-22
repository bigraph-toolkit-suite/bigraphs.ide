import type { EvoState } from './EvoState.js';
import type { EvoHistory } from './EvoHistory.js';
import type { VsCodeApi } from './types.js';
import {
	RuleApplicationStrategy,
	RULE_APPLICATION_STRATEGY_LABELS,
	normalizeRuleApplicationStrategy,
} from '../ruleApplicationStrategy.js';

export class EvoForm {
	private readonly state: EvoState;
	private readonly history: EvoHistory;
	private readonly vscode: VsCodeApi;

	// DOM references
	private readonly placeholderEl: HTMLElement;
	private readonly formEl: HTMLElement;
	private readonly configPathBarEl: HTMLElement;
	private readonly actionBarEl: HTMLElement;
	private readonly btnPlay: HTMLButtonElement;
	private readonly btnPause: HTMLButtonElement;
	private readonly evolutionLabelEl: HTMLInputElement;
	private readonly btnCloseProject: HTMLButtonElement;
	private readonly maxOpsEnabledEl: HTMLInputElement;
	private readonly maxOpsEl: HTMLInputElement;
	private readonly checkpointFileGenEl: HTMLInputElement;
	private readonly visualizeStepsEl: HTMLInputElement;
	private readonly ruleApplicationStrategyEl: HTMLSelectElement;

	constructor(state: EvoState, history: EvoHistory, vscode: VsCodeApi) {
		this.state   = state;
		this.history = history;
		this.vscode  = vscode;

		this.placeholderEl      = document.getElementById('placeholder')!;
		this.formEl             = document.getElementById('form')!;
		this.configPathBarEl    = document.getElementById('config-path-bar')!;
		this.actionBarEl        = document.getElementById('action-bar')!;
		this.btnPlay            = document.getElementById('btnPlay')  as HTMLButtonElement;
		this.btnPause           = document.getElementById('btnPause') as HTMLButtonElement;
		this.evolutionLabelEl   = document.getElementById('evolutionLabel')           as HTMLInputElement;
		this.btnCloseProject    = document.getElementById('btnCloseProject')         as HTMLButtonElement;
		this.maxOpsEnabledEl    = document.getElementById('maxOperationsEnabled')     as HTMLInputElement;
		this.maxOpsEl           = document.getElementById('maxOperations')            as HTMLInputElement;
		this.checkpointFileGenEl = document.getElementById('checkpointFileGeneration') as HTMLInputElement;
		this.visualizeStepsEl   = document.getElementById('visualizeIntermediateSteps') as HTMLInputElement;
		this.ruleApplicationStrategyEl = document.getElementById('ruleApplicationStrategy') as HTMLSelectElement;
		this.ruleApplicationStrategyEl.replaceChildren();
		for (const strategy of [RuleApplicationStrategy.FirstFirst, RuleApplicationStrategy.RoundRobin]) {
			const opt = document.createElement('option');
			opt.value = strategy;
			opt.textContent = RULE_APPLICATION_STRATEGY_LABELS[strategy];
			this.ruleApplicationStrategyEl.appendChild(opt);
		}

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

	setRunningState(running: boolean): void {
		this.btnPlay.disabled  = running;
		this.btnPause.disabled = !running;
		this.btnPlay.style.display  = running ? 'none' : '';
		this.btnPause.style.display = running ? ''     : 'none';
	}

	setActionBarVisible(visible: boolean): void {
		this.actionBarEl.style.display = visible ? '' : 'none';
	}

	applyMaxOpsEnabledState(): void {
		this.maxOpsEl.disabled = !this.maxOpsEnabledEl.checked;
	}

	// ── Form show/hide ────────────────────────────────────────────────────────

	showEvolutionForm(visible: boolean): void {
		if (visible) {
			this.placeholderEl.textContent   = '';
			this.placeholderEl.style.display = 'none';
			this.formEl.classList.add('visible');
			this.setActionBarVisible(true);
			this.history.setVisible(true);
		} else {
			this.placeholderEl.textContent   = 'No evolution selected. Click an entry in the Evolutions list above.';
			this.placeholderEl.style.display = '';
			this.formEl.classList.remove('visible');
			this.setActionBarVisible(false);
			this.history.setVisible(false);
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
				visualizeIntermediateSteps: this.visualizeStepsEl.checked,
				ruleApplicationStrategy:    this.ruleApplicationStrategyEl.value as RuleApplicationStrategy,
				workspaceBigraph:           st.currentWorkspaceBigraph,
			},
		});
	}

	collectPayload(actionType: string): Record<string, unknown> {
		const st = this.state;
		return {
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
			visualizeIntermediateSteps: this.visualizeStepsEl.checked,
			ruleApplicationStrategy:    this.ruleApplicationStrategyEl.value as RuleApplicationStrategy,
		};
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
	setVisualizeSteps(value: boolean): void   { this.visualizeStepsEl.checked      = value; }
	setRuleApplicationStrategy(value: string): void {
		this.ruleApplicationStrategyEl.value = normalizeRuleApplicationStrategy(value);
	}

	// ── Private ───────────────────────────────────────────────────────────────

	private initListeners(): void {
		this.btnPlay.addEventListener('click', () => {
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
		this.visualizeStepsEl.addEventListener('change',     () => this.reportFormState());
		this.ruleApplicationStrategyEl.addEventListener('change', () => this.reportFormState());
	}
}
