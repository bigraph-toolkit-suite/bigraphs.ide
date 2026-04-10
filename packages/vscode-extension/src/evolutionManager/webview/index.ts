import { EvoState }        from './EvoState.js';
import { EvoHistory }       from './EvoHistory.js';
import { EvoRules }         from './EvoRules.js';
import { EvoVerification }  from './EvoVerification.js';
import { EvoTree }          from './EvoTree.js';
import { EvoForm }          from './EvoForm.js';
import { EvoMessages }      from './EvoMessages.js';
import type { VsCodeApi }   from './types.js';
import { WebviewDndBridge } from './dnd/WebviewDndBridge.js';

declare function acquireVsCodeApi(): VsCodeApi;

(function boot(): void {
	const vscode   = (typeof acquireVsCodeApi !== 'undefined' ? acquireVsCodeApi() : null) as VsCodeApi;
	const state    = new EvoState();
	const history  = new EvoHistory();
	const rules    = new EvoRules(state, vscode);
	const verif    = new EvoVerification(state, vscode);
	const tree     = new EvoTree(vscode);
	const form     = new EvoForm(state, history, vscode);
	const msgs     = new EvoMessages(state, history, rules, verif, tree, form, vscode);
	const dndBridge = new WebviewDndBridge(vscode);

	// Wire rules back to form-state reporting after active-tag changes
	rules.setOnChanged(() => form.reportFormState());

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
