import * as vscode from 'vscode';
import * as path from 'path';
import { BigraphExplorerProvider, BigraphExplorerDragAndDropController } from './bigraphFileExplorerProvider';
import {
    RewriteRuleExplorerProvider,
    RewriteRuleDragAndDropController,
    addRewriteRuleSet,
    deleteRewriteRule,
    deleteRewriteRuleSet,
    initRewriteRulesConfig,
    openBigraphFromWorkspaceRelativePath,
    openRewriteRuleInSplit,
    saveRewriteRuleFromEditor
} from '../rewriteRuleExplorer/rewriteRuleExplorerProvider';
import { getHtmlViewProvider } from '../rewriteRuleEditor/htmlViewProvider';
import { getEvolutionManagerViewProvider } from '../evolutionManager/evolutionManagerViewProvider';
import { dispatchActionToActiveEditor } from '../editor/init';

let bigraphExplorerProvider: BigraphExplorerProvider;
let dragAndDropController: BigraphExplorerDragAndDropController;
let rewriteRuleExplorerProvider: RewriteRuleExplorerProvider;
let rewriteRuleDragController: RewriteRuleDragAndDropController;
const RULE_EDITOR_VISIBLE_CONTEXT_KEY = 'bigraph.showRewriteRuleEditor';

/** Returns the URI of the first open tab that displays an XMI file (.xmi), or undefined. */
function getFirstOpenXmiTabUri(): vscode.Uri | undefined {
    for (const group of vscode.window.tabGroups.all) {
        for (const tab of group.tabs) {
            const input = tab.input as { uri?: vscode.Uri; resource?: vscode.Uri } | undefined;
            const uri = input instanceof vscode.Uri
                ? input
                : input?.uri ?? input?.resource;
            if (uri?.fsPath?.toLowerCase().endsWith('.xmi')) {
                return uri;
            }
        }
    }
    return undefined;
}

export function initExplorerSidebar(): vscode.Disposable[] {
    bigraphExplorerProvider = new BigraphExplorerProvider();
    dragAndDropController = new BigraphExplorerDragAndDropController();

    const bigraphTreeView = vscode.window.createTreeView('bigraphExplorer', {
        treeDataProvider: bigraphExplorerProvider,
        dragAndDropController: dragAndDropController
    });

    rewriteRuleExplorerProvider = new RewriteRuleExplorerProvider();
    rewriteRuleDragController = new RewriteRuleDragAndDropController();
    const rewriteRulesTreeView = vscode.window.createTreeView('rewriteRules', {
        treeDataProvider: rewriteRuleExplorerProvider,
        dragAndDropController: rewriteRuleDragController
    });

    const openRewriteRuleCommand = vscode.commands.registerCommand(
        'bigraph.openRewriteRuleInSplit',
        async (rule: unknown) => {
            if (!rule || typeof rule !== 'object') {
                return;
            }
            const maybeRule = rule as { label?: unknown; leftPath?: unknown; rightPath?: unknown };
            if (
                typeof maybeRule.label !== 'string' ||
                typeof maybeRule.leftPath !== 'string' ||
                typeof maybeRule.rightPath !== 'string'
            ) {
                return;
            }
            await openRewriteRuleInSplit({
                label: maybeRule.label,
                leftPath: maybeRule.leftPath,
                rightPath: maybeRule.rightPath
            });
        }
    );

    const initRewriteRulesConfigCommand = vscode.commands.registerCommand(
        'bigraph.initRewriteRulesConfig',
        async () => {
            await initRewriteRulesConfig();
            rewriteRuleExplorerProvider.refresh();
        }
    );

    const addRewriteRuleSetCommand = vscode.commands.registerCommand(
        'bigraph.addRewriteRuleSet',
        async () => {
            await addRewriteRuleSet();
            rewriteRuleExplorerProvider.refresh();
        }
    );

    const addRewriteRuleCommand = vscode.commands.registerCommand(
        'bigraph.addRewriteRule',
        async (ruleSet: unknown) => {
            if (!ruleSet || typeof ruleSet !== 'object') {
                return;
            }
            const maybeSet = ruleSet as { label?: unknown; setIndex?: unknown };
            if (typeof maybeSet.label !== 'string') {
                return;
            }
            const htmlProvider = getHtmlViewProvider();
            if (!htmlProvider) {
                vscode.window.showInformationMessage('Open "Rewrite Rule Editor" once and retry.');
                return;
            }
            await vscode.commands.executeCommand('setContext', RULE_EDITOR_VISIBLE_CONTEXT_KEY, true);
            htmlProvider.startNewRuleEditor({
                setLabel: maybeSet.label,
                setIndex: typeof maybeSet.setIndex === 'number' ? maybeSet.setIndex : undefined
            });
            await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
            await vscode.commands.executeCommand('bigraphHtmlView.focus');
        }
    );

    const editRewriteRuleCommand = vscode.commands.registerCommand(
        'bigraph.editRewriteRule',
        async (rule: unknown) => {
            if (!rule || typeof rule !== 'object') {
                return;
            }
            const maybeRule = rule as {
                label?: unknown;
                leftPath?: unknown;
                rightPath?: unknown;
                setLabel?: unknown;
                setIndex?: unknown;
            };
            if (
                typeof maybeRule.label !== 'string' ||
                typeof maybeRule.leftPath !== 'string' ||
                typeof maybeRule.rightPath !== 'string' ||
                typeof maybeRule.setLabel !== 'string'
            ) {
                return;
            }

            await openRewriteRuleInSplit({
                label: maybeRule.label,
                leftPath: maybeRule.leftPath,
                rightPath: maybeRule.rightPath,
                setLabel: maybeRule.setLabel,
                setIndex: typeof maybeRule.setIndex === 'number' ? maybeRule.setIndex : undefined
            });

            const htmlProvider = getHtmlViewProvider();
            if (!htmlProvider) {
                vscode.window.showInformationMessage('Open "Rewrite Rule Editor" once and retry.');
                return;
            }
            await vscode.commands.executeCommand('setContext', RULE_EDITOR_VISIBLE_CONTEXT_KEY, true);
            htmlProvider.startEditRuleEditor({
                setLabel: maybeRule.setLabel,
                setIndex: typeof maybeRule.setIndex === 'number' ? maybeRule.setIndex : undefined,
                label: maybeRule.label,
                leftPath: maybeRule.leftPath,
                rightPath: maybeRule.rightPath
            });
            await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
            await vscode.commands.executeCommand('bigraphHtmlView.focus');
        }
    );

    const saveRewriteRuleCommand = vscode.commands.registerCommand(
        'bigraph.saveRewriteRuleFromEditor',
        async (input: unknown) => {
            if (!input || typeof input !== 'object') {
                return;
            }
            const maybeInput = input as {
                mode?: unknown;
                setLabel?: unknown;
                setIndex?: unknown;
                label?: unknown;
                leftPath?: unknown;
                rightPath?: unknown;
                originalLabel?: unknown;
            };
            if (
                (maybeInput.mode !== 'new' && maybeInput.mode !== 'edit') ||
                typeof maybeInput.setLabel !== 'string' ||
                typeof maybeInput.label !== 'string' ||
                typeof maybeInput.leftPath !== 'string' ||
                typeof maybeInput.rightPath !== 'string'
            ) {
                return;
            }

            await saveRewriteRuleFromEditor({
                mode: maybeInput.mode,
                setLabel: maybeInput.setLabel,
                setIndex: typeof maybeInput.setIndex === 'number' ? maybeInput.setIndex : undefined,
                label: maybeInput.label,
                leftPath: maybeInput.leftPath,
                rightPath: maybeInput.rightPath,
                originalLabel: typeof maybeInput.originalLabel === 'string' ? maybeInput.originalLabel : undefined
            });
            await vscode.commands.executeCommand('setContext', RULE_EDITOR_VISIBLE_CONTEXT_KEY, false);
            rewriteRuleExplorerProvider.refresh();
        }
    );

    const closeRewriteRuleEditorCommand = vscode.commands.registerCommand(
        'bigraph.closeRewriteRuleEditor',
        async () => {
            await vscode.commands.executeCommand('setContext', RULE_EDITOR_VISIBLE_CONTEXT_KEY, false);
        }
    );

    const deleteRewriteRuleSetCommand = vscode.commands.registerCommand(
        'bigraph.deleteRewriteRuleSet',
        async (ruleSet: unknown) => {
            if (!ruleSet || typeof ruleSet !== 'object') {
                return;
            }
            const maybeSet = ruleSet as { label?: unknown; setIndex?: unknown };
            if (typeof maybeSet.label !== 'string') {
                return;
            }
            await deleteRewriteRuleSet({
                label: maybeSet.label,
                setIndex: typeof maybeSet.setIndex === 'number' ? maybeSet.setIndex : undefined
            });
            rewriteRuleExplorerProvider.refresh();
        }
    );

    const deleteRewriteRuleCommand = vscode.commands.registerCommand(
        'bigraph.deleteRewriteRule',
        async (rule: unknown) => {
            if (!rule || typeof rule !== 'object') {
                return;
            }
            const maybeRule = rule as {
                label?: unknown;
                leftPath?: unknown;
                rightPath?: unknown;
                setLabel?: unknown;
                setIndex?: unknown;
            };
            if (
                typeof maybeRule.label !== 'string' ||
                typeof maybeRule.leftPath !== 'string' ||
                typeof maybeRule.rightPath !== 'string'
            ) {
                return;
            }
            await deleteRewriteRule({
                label: maybeRule.label,
                leftPath: maybeRule.leftPath,
                rightPath: maybeRule.rightPath,
                setLabel: typeof maybeRule.setLabel === 'string' ? maybeRule.setLabel : undefined,
                setIndex: typeof maybeRule.setIndex === 'number' ? maybeRule.setIndex : undefined
            });
            rewriteRuleExplorerProvider.refresh();
        }
    );

    const openRewriteRuleEditorSideCommand = vscode.commands.registerCommand(
        'bigraph.openRewriteRuleEditorSide',
        async (relativePath: unknown) => {
            if (typeof relativePath !== 'string' || relativePath.trim().length === 0) {
                return;
            }
            await openBigraphFromWorkspaceRelativePath(relativePath);
        }
    );

    const composeBigraphCommand = vscode.commands.registerCommand(
        'bigraph.composeBigraph',
        async (item: unknown) => {
            if (!item || typeof item !== 'object') { return; }
            const maybeFile = item as { label?: unknown; uri?: vscode.Uri };
            if (typeof maybeFile.label !== 'string' || !maybeFile.uri) { return; }

            // Determine the currently active canvas bigraph (focused .xmi tab).
            const activeUri = vscode.window.activeTextEditor?.document.uri
                ?? vscode.window.tabGroups.activeTabGroup.activeTab?.input;
            let canvasName: string | undefined;
            // Try to find the name from the active custom editor tab (bigraph diagram)
            for (const group of vscode.window.tabGroups.all) {
                for (const tab of group.tabs) {
                    const input = tab.input as { uri?: vscode.Uri; resource?: vscode.Uri } | undefined;
                    const uri = input instanceof vscode.Uri
                        ? input
                        : (input as any)?.uri ?? (input as any)?.resource;
                    if (uri?.fsPath?.toLowerCase().endsWith('.xmi')
                        && !uri.fsPath.endsWith('.signature.xmi')
                        && tab.isActive) {
                        canvasName = path.basename(uri.fsPath, '.xmi');
                        break;
                    }
                }
                if (canvasName) { break; }
            }
            // Fall back to first open XMI tab if nothing is active
            if (!canvasName) {
                const fallback = getFirstOpenXmiTabUri();
                canvasName = fallback ? path.basename(fallback.fsPath, '.xmi') : '(no bigraph open)';
            }

            const sourceName = maybeFile.label as string;

            const choice = await vscode.window.showQuickPick(
                [
                    {
                        label: '$(type-hierarchy-sub) Parallel (||)',
                        description: 'Tensor product — place both bigraphs side by side (always works)',
                        operator: 'parallel'
                    },
                    {
                        label: '$(arrow-right) Sequential (∘)',
                        description: 'Composition — canvas bigraph must have sites matching the imported bigraph\'s outer names',
                        operator: 'sequential'
                    },
                    {
                        label: '$(close) Abort',
                        description: 'Cancel composition',
                        operator: 'abort'
                    }
                ],
                {
                    title: `Compose "${canvasName}" ← "${sourceName}"`,
                    placeHolder: 'Choose a composition operator'
                }
            );

            if (!choice || choice.operator === 'abort') { return; }

            dispatchActionToActiveEditor({
                kind: 'bigraph.compose',
                operator: choice.operator,
                sourcePath: maybeFile.uri.fsPath
            });
        }
    );

    const addToVerificationCommand = vscode.commands.registerCommand(
        'bigraph.addToVerification',
        async (item: unknown) => {
            if (!item || typeof item !== 'object') { return; }
            const maybeFile = item as { label?: unknown; uri?: vscode.Uri };
            if (typeof maybeFile.label !== 'string' || !maybeFile.uri) { return; }

            const evolutionManager = getEvolutionManagerViewProvider();
            if (!evolutionManager) {
                vscode.window.showWarningMessage('Evolution Manager is not open.');
                return;
            }
            evolutionManager.postMessage({
                type: 'addVerificationBigraph',
                label: maybeFile.label,
                fsPath: maybeFile.uri.fsPath
            });
        }
    );

    const playRewriteRuleOrSetCommand = vscode.commands.registerCommand(
        'bigraph.playRewriteRuleOrSet',
        async (item: unknown) => {
            if (!item || typeof item !== 'object') {
                return;
            }
            const obj = item as {
                label?: unknown;
                rules?: { label: string; leftPath: string; rightPath: string }[];
                leftPath?: unknown;
                rightPath?: unknown;
            };
            const evolutionLabel = typeof obj.label === 'string' ? obj.label : 'rule/set';
            const rewriteRules: { label: string; redexPath: string; reactumPath: string }[] = Array.isArray(obj.rules)
                ? obj.rules
                    .filter((r) => r.leftPath && r.rightPath)
                    .map((r) => ({ label: r.label ?? '', redexPath: r.leftPath, reactumPath: r.rightPath }))
                : typeof obj.leftPath === 'string' && typeof obj.rightPath === 'string'
                  ? [{ label: evolutionLabel, redexPath: obj.leftPath, reactumPath: obj.rightPath }]
                  : [];

            const xmiUri = getFirstOpenXmiTabUri();
            const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
            const bigraphPath =
                xmiUri && workspaceRoot
                    ? vscode.workspace.asRelativePath(xmiUri)
                    : xmiUri?.fsPath ?? '';

            const evolutionManager = getEvolutionManagerViewProvider();
            if (evolutionManager) {
                // Mark as new evolution before filling the form
                evolutionManager.setNewEvolution();
                evolutionManager.postMessage({
                    type: 'fillEvolutionForm',
                    evolutionLabel,
                    bigraphPath,
                    rewriteRules
                });
                await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
                await vscode.commands.executeCommand('evolutionManagerView.focus');
            }

            if (!xmiUri) {
                vscode.window.showWarningMessage('No open tab with an XMI file. Open a .xmi diagram first to use it as the bigraph.');
            }
        }
    );

    return [
        bigraphTreeView,
        rewriteRulesTreeView,
        openRewriteRuleCommand,
        initRewriteRulesConfigCommand,
        addRewriteRuleSetCommand,
        addRewriteRuleCommand,
        editRewriteRuleCommand,
        saveRewriteRuleCommand,
        closeRewriteRuleEditorCommand,
        deleteRewriteRuleSetCommand,
        deleteRewriteRuleCommand,
        openRewriteRuleEditorSideCommand,
        playRewriteRuleOrSetCommand,
        addToVerificationCommand,
        composeBigraphCommand
    ];
}

export function getBigraphExplorerProvider(): BigraphExplorerProvider {
    return bigraphExplorerProvider;
}
