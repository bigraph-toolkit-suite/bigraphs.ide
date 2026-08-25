import type { RewritingMode } from './RewritingMode.js';
import { RULE_APPLICATION_STRATEGY_LABELS } from '../../ruleApplicationStrategy.js';

/**
 * The built-in default mode: classic evolution runs with stop-condition
 * verification bigraphs and per-rule play. Always registered and always the
 * first tab.
 */
export const evolutionMode: RewritingMode = {
	id: 'evolution',
	label: 'Evolution',
	ui: {
		verificationLabel: 'Verification',
		verificationTagText: 'stop',
		strategyLabels: RULE_APPLICATION_STRATEGY_LABELS,
		strategyTitle: 'How rewrite rules are tried on each step',
		showVerification: true,
		showStrategy: true,
		showMaxOperations: true,
		showCheckpointFileGeneration: true,
		showRuleList: true,
		perRulePlay: true,
		showRuleSubtitle: true,
	},
};
