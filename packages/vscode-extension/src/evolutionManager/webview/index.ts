import { EvoState }        from './EvoState.js';
import { EvoRules }         from './EvoRules.js';
import { EvoVerification }  from './EvoVerification.js';
import { EvoTree }          from './EvoTree.js';
import { EvoForm }          from './EvoForm.js';
import { EvoMessages }      from './EvoMessages.js';
import { ModeController }   from './modes/ModeController.js';
import { getRewritingModes } from './modes/index.js';
import type { VsCodeApi }   from './types.js';
import { WebviewDndBridge } from './dnd/WebviewDndBridge.js';

declare function acquireVsCodeApi(): VsCodeApi;

(function boot(): void {
	const vscode   = (typeof acquireVsCodeApi !== 'undefined' ? acquireVsCodeApi() : null) as VsCodeApi;
	const state    = new EvoState();
	const modes    = new ModeController(getRewritingModes(), state, vscode);
	const rules    = new EvoRules(state, vscode, modes);
	const verif    = new EvoVerification(state, vscode, modes);
	const tree     = new EvoTree(vscode, state);
	const form     = new EvoForm(state, vscode, modes);
	const msgs     = new EvoMessages(state, rules, verif, tree, form, modes, vscode);
	const dndBridge = new WebviewDndBridge(vscode);

	// Wire rules back to form-state reporting after active-tag changes
	rules.setOnChanged(() => form.reportFormState());
	rules.setOnPlayRule((ruleId) => {
		if (vscode) {
			vscode.postMessage(form.collectPlayRulePayload(ruleId));
		}
	});

	// Mode switches re-apply the declarative UI and re-render the shared lists.
	modes.init(() => {
		form.applyModeUi();
		rules.render(state.lastRewriteRules);
		verif.render(state.lastVerificationBigraphs);
	});

	msgs.init();
	const verificationEl = document.getElementById('verificationList');
	if (verificationEl) {
		dndBridge.registerDropZonePayload(
			verificationEl,
			'bigraphFiles',
			(payload) => verif.addBigraphFilesFromDragPayload(payload),
			'evo.verification',
			10
		);
	}
	const rewriteRulesEl = document.getElementById('rewriteRulesList');
	if (rewriteRulesEl) {
		dndBridge.registerDropZonePayload(
			rewriteRulesEl,
			'rewriteRulePayload',
			(payload) => rules.addRewriteRulesFromDragPayload(payload),
			'evo.rules',
			10
		);
	}
	dndBridge.init();

	if (vscode) {
		vscode.postMessage({ type: 'webviewReady' });
	}
}());
