import { EvoState }        from './EvoState.js';
import { EvoHistory }       from './EvoHistory.js';
import { EvoRules }         from './EvoRules.js';
import { EvoVerification }  from './EvoVerification.js';
import { EvoTree }          from './EvoTree.js';
import { EvoForm }          from './EvoForm.js';
import { EvoMessages }      from './EvoMessages.js';
import type { VsCodeApi }   from './types.js';

declare function acquireVsCodeApi(): VsCodeApi;

(function boot(): void {
	const vscode   = (typeof acquireVsCodeApi !== 'undefined' ? acquireVsCodeApi() : null) as VsCodeApi;
	const state    = new EvoState();
	const history  = new EvoHistory();
	const rules    = new EvoRules(state);
	const verif    = new EvoVerification(state, vscode);
	const tree     = new EvoTree(vscode);
	const form     = new EvoForm(state, history, vscode);
	const msgs     = new EvoMessages(state, history, rules, verif, tree, form, vscode);

	// Wire rules back to form-state reporting after active-tag changes
	rules.setOnChanged(() => form.reportFormState());

	msgs.init();

	if (vscode) {
		vscode.postMessage({ type: 'webviewReady' });
	}
}());
