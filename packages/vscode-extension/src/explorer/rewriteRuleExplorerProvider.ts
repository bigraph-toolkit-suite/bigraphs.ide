import * as path from 'path';
import * as vscode from 'vscode';

const WORKSPACE_CONFIG_FILE = 'bigraph-workspace.json';

type RewriteRuleNode = RewriteRuleSetItem | RewriteRuleItem;
type RewriteRuleTreeNode = RewriteRuleNode | InitRulesItem;

interface BigraphWorkspaceFile {
    ruleSets?: BigraphWorkspaceRuleSet[];
    'rule sets'?: BigraphWorkspaceRuleSet[];
}

interface BigraphWorkspaceRuleSet {
    label: string;
    rules: BigraphWorkspaceRule[];
}

interface BigraphWorkspaceRule {
    label: string;
    left: string;
    right: string;
    leftFile?: string;
    rightFile?: string;
}

interface SelectedRewriteRule {
    label: string;
    leftPath: string;
    rightPath: string;
    setLabel?: string;
    setIndex?: number;
}

interface SelectedRewriteRuleSet {
    label: string;
    setIndex?: number;
}

export interface SaveRewriteRuleInput {
    mode: 'new' | 'edit';
    setLabel: string;
    setIndex?: number;
    label: string;
    leftPath: string;
    rightPath: string;
    originalLabel?: string;
}

export class RewriteRuleExplorerProvider implements vscode.TreeDataProvider<RewriteRuleTreeNode> {
    private readonly _onDidChangeTreeData = new vscode.EventEmitter<RewriteRuleTreeNode | undefined | null | void>();
    readonly onDidChangeTreeData: vscode.Event<RewriteRuleTreeNode | undefined | null | void> = this._onDidChangeTreeData.event;

    constructor() {
        vscode.workspace.onDidCreateFiles(() => this.refresh());
        vscode.workspace.onDidDeleteFiles(() => this.refresh());
        vscode.workspace.onDidRenameFiles(() => this.refresh());
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (path.basename(document.uri.fsPath) === WORKSPACE_CONFIG_FILE) {
                this.refresh();
            }
        });
    }

    refresh(): void {
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: RewriteRuleTreeNode): vscode.TreeItem {
        return element;
    }

    async getChildren(element?: RewriteRuleTreeNode): Promise<RewriteRuleTreeNode[]> {
        if (element instanceof RewriteRuleSetItem) {
            return element.rules;
        }
        if (element instanceof RewriteRuleItem || element instanceof InitRulesItem) {
            return [];
        }

        const workspaceConfig = await this.readWorkspaceConfig();
        if (!workspaceConfig.exists) {
            return [new InitRulesItem()];
        }

        return workspaceConfig.ruleSets.map((ruleSet, setIndex) => {
            const rules = ruleSet.rules.map((rule) =>
                new RewriteRuleItem(rule.label, rule.left, rule.right, ruleSet.label, setIndex)
            );
            return new RewriteRuleSetItem(ruleSet.label, rules, setIndex);
        });
    }

    private async readWorkspaceConfig(): Promise<{ exists: boolean; ruleSets: BigraphWorkspaceRuleSet[] }> {
        const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
        if (!workspaceRoot) {
            return { exists: false, ruleSets: [] };
        }

        const workspaceConfigUri = vscode.Uri.joinPath(workspaceRoot.uri, WORKSPACE_CONFIG_FILE);
        let jsonText: string;
        try {
            const buffer = await vscode.workspace.fs.readFile(workspaceConfigUri);
            jsonText = Buffer.from(buffer).toString('utf8');
        } catch {
            return { exists: false, ruleSets: [] };
        }

        try {
            const parsed = JSON.parse(jsonText) as BigraphWorkspaceFile;
            const ruleSets = Array.isArray(parsed.ruleSets)
                ? parsed.ruleSets
                : Array.isArray(parsed['rule sets'])
                    ? parsed['rule sets']
                    : [];
            const validRuleSets = ruleSets
                .filter((ruleSet) => typeof ruleSet?.label === 'string' && Array.isArray(ruleSet.rules))
                .map((ruleSet) => ({
                    label: ruleSet.label,
                    rules: ruleSet.rules
                        .filter(
                            (rule) =>
                                typeof rule?.label === 'string' &&
                                typeof (rule.left ?? rule.leftFile) === 'string' &&
                                typeof (rule.right ?? rule.rightFile) === 'string'
                        )
                        .map((rule) => ({
                            label: rule.label,
                            left: rule.left ?? rule.leftFile ?? '',
                            right: rule.right ?? rule.rightFile ?? ''
                        }))
                }));

            return { exists: true, ruleSets: validRuleSets };
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showWarningMessage(`Failed to parse ${WORKSPACE_CONFIG_FILE}: ${message}`);
            return { exists: true, ruleSets: [] };
        }
    }
}

export async function initRewriteRulesConfig(): Promise<void> {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceRoot) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const workspaceConfigUri = vscode.Uri.joinPath(workspaceRoot.uri, WORKSPACE_CONFIG_FILE);
    try {
        await vscode.workspace.fs.stat(workspaceConfigUri);
        await vscode.window.showTextDocument(workspaceConfigUri, { preview: false });
        return;
    } catch {
        // File does not exist, create it below.
    }

    const emptyConfig = JSON.stringify({ ruleSets: [] }, null, 2) + '\n';
    await vscode.workspace.fs.writeFile(workspaceConfigUri, Buffer.from(emptyConfig, 'utf8'));
    await vscode.window.showTextDocument(workspaceConfigUri, { preview: false });
}

export async function addRewriteRuleSet(): Promise<void> {
    const label = await vscode.window.showInputBox({
        title: 'Create Rewrite Rule Set',
        prompt: 'Enter the label of the new rule set',
        ignoreFocusOut: true,
        validateInput: (value) => value.trim().length === 0 ? 'Rule set label is required' : undefined
    });
    if (!label) {
        return;
    }

    const { uri, config } = await readWritableWorkspaceConfig();
    if (!uri) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    config.ruleSets.push({
        label: label.trim(),
        rules: []
    });

    await writeWorkspaceConfig(uri, config);
}

export async function saveRewriteRuleFromEditor(input: SaveRewriteRuleInput): Promise<void> {
    const { uri, config } = await readWritableWorkspaceConfig();
    if (!uri) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const targetIndex = typeof input.setIndex === 'number' && config.ruleSets[input.setIndex]
        ? input.setIndex
        : config.ruleSets.findIndex((set) => set.label === input.setLabel);
    if (targetIndex < 0) {
        vscode.window.showErrorMessage(`Rule set "${input.setLabel}" was not found in ${WORKSPACE_CONFIG_FILE}`);
        return;
    }

    if (input.mode === 'edit' && input.originalLabel) {
        const ruleIndex = config.ruleSets[targetIndex].rules.findIndex((rule) => rule.label === input.originalLabel);
        if (ruleIndex >= 0) {
            config.ruleSets[targetIndex].rules[ruleIndex] = {
                label: input.label.trim(),
                left: input.leftPath.trim(),
                right: input.rightPath.trim()
            };
        } else {
            config.ruleSets[targetIndex].rules.push({
                label: input.label.trim(),
                left: input.leftPath.trim(),
                right: input.rightPath.trim()
            });
        }
    } else {
        config.ruleSets[targetIndex].rules.push({
            label: input.label.trim(),
            left: input.leftPath.trim(),
            right: input.rightPath.trim()
        });
    }

    await writeWorkspaceConfig(uri, config);
}

export async function deleteRewriteRuleSet(selection: SelectedRewriteRuleSet): Promise<void> {
    const { uri, config } = await readWritableWorkspaceConfig();
    if (!uri) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const targetIndex = typeof selection.setIndex === 'number' && config.ruleSets[selection.setIndex]
        ? selection.setIndex
        : config.ruleSets.findIndex((set) => set.label === selection.label);
    if (targetIndex < 0) {
        vscode.window.showErrorMessage(`Rule set "${selection.label}" was not found in ${WORKSPACE_CONFIG_FILE}`);
        return;
    }

    config.ruleSets.splice(targetIndex, 1);
    await writeWorkspaceConfig(uri, config);
}

export async function deleteRewriteRule(selection: SelectedRewriteRule): Promise<void> {
    const { uri, config } = await readWritableWorkspaceConfig();
    if (!uri) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const setIndex = typeof selection.setIndex === 'number' && config.ruleSets[selection.setIndex]
        ? selection.setIndex
        : config.ruleSets.findIndex((set) => set.label === selection.setLabel);
    if (setIndex < 0) {
        vscode.window.showErrorMessage(`Rule set "${selection.setLabel ?? ''}" was not found in ${WORKSPACE_CONFIG_FILE}`);
        return;
    }

    const rules = config.ruleSets[setIndex].rules;
    const ruleIndex = rules.findIndex((rule) =>
        rule.label === selection.label &&
        rule.left === selection.leftPath &&
        rule.right === selection.rightPath
    );
    if (ruleIndex < 0) {
        vscode.window.showErrorMessage(`Rule "${selection.label}" was not found in set "${config.ruleSets[setIndex].label}".`);
        return;
    }

    rules.splice(ruleIndex, 1);
    await writeWorkspaceConfig(uri, config);
}

export async function openRewriteRuleInSplit(rule: SelectedRewriteRule): Promise<void> {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceRoot) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const leftUri = vscode.Uri.joinPath(workspaceRoot.uri, rule.leftPath);
    const rightUri = vscode.Uri.joinPath(workspaceRoot.uri, rule.rightPath);

    try {
        await vscode.workspace.fs.stat(leftUri);
        await vscode.workspace.fs.stat(rightUri);
    } catch {
        vscode.window.showErrorMessage(`Could not open rule "${rule.label}" because one or both files do not exist.`);
        return;
    }

    const allOpenTabs = vscode.window.tabGroups.all.flatMap((group) => group.tabs);
    if (allOpenTabs.length > 1) {
        const occupiedGroups = vscode.window.tabGroups.all
            .filter((group) => group.tabs.length > 0)
            .sort((a, b) => {
                const aCol = typeof a.viewColumn === 'number' ? a.viewColumn : Number.MAX_SAFE_INTEGER;
                const bCol = typeof b.viewColumn === 'number' ? b.viewColumn : Number.MAX_SAFE_INTEGER;
                return aCol - bCol;
            });

        const firstColumn = occupiedGroups[0]?.viewColumn ?? vscode.ViewColumn.Active;
        const secondColumn = occupiedGroups[1]?.viewColumn ?? firstColumn;

        await vscode.commands.executeCommand('vscode.openWith', leftUri, 'bigraph.glspDiagram', {
            preview: false,
            viewColumn: firstColumn,
            preserveFocus: true
        });
        await vscode.commands.executeCommand('vscode.openWith', rightUri, 'bigraph.glspDiagram', {
            preview: false,
            viewColumn: secondColumn
        });
        return;
    }

    await vscode.commands.executeCommand('vscode.openWith', leftUri, 'bigraph.glspDiagram', {
        preview: false,
        viewColumn: vscode.ViewColumn.Active
    });
    await vscode.commands.executeCommand('vscode.openWith', rightUri, 'bigraph.glspDiagram', {
        preview: false,
        viewColumn: vscode.ViewColumn.Beside
    });
}

export async function openBigraphFromWorkspaceRelativePath(relativePath: string): Promise<void> {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceRoot) {
        vscode.window.showErrorMessage('No workspace open');
        return;
    }

    const uri = vscode.Uri.joinPath(workspaceRoot.uri, relativePath);
    try {
        await vscode.workspace.fs.stat(uri);
    } catch {
        vscode.window.showErrorMessage(`Could not open "${relativePath}" because the file does not exist.`);
        return;
    }

    await vscode.commands.executeCommand('vscode.openWith', uri, 'bigraph.glspDiagram', {
        preview: false,
        viewColumn: vscode.ViewColumn.Active
    });
}

const REWRITE_RULE_DRAG_MIME = 'application/vnd.bigraphide.rewrite-rule';

export interface DraggedRewriteRulePayload {
    evolutionLabel: string;
    rewriteRules: { setLabel: string; label: string }[];
}

/**
 * Drag controller for Rewrite Rules tree. Allows dragging a rule set or a single rule
 * (e.g. onto the diagram canvas to fill the Evolution Manager).
 */
export class RewriteRuleDragAndDropController implements vscode.TreeDragAndDropController<RewriteRuleTreeNode> {
    dragMimeTypes = [REWRITE_RULE_DRAG_MIME];
    dropMimeTypes: string[] = [];

    private onDragStartCallback?: (payload: DraggedRewriteRulePayload) => void;

    setOnDragStart(callback: (payload: DraggedRewriteRulePayload) => void): void {
        this.onDragStartCallback = callback;
    }

    handleDrag(source: RewriteRuleTreeNode[], dataTransfer: vscode.DataTransfer, _token: vscode.CancellationToken): void {
        const item = source[0];
        if (!item || item instanceof InitRulesItem) {
            return;
        }
        let payload: DraggedRewriteRulePayload;
        if (item instanceof RewriteRuleSetItem) {
            payload = {
                evolutionLabel: item.label,
                rewriteRules: item.rules.map((r) => ({ setLabel: item.label, label: r.label }))
            };
        } else {
            payload = {
                evolutionLabel: item.label,
                rewriteRules: [{ setLabel: item.setLabel, label: item.label }]
            };
        }
        dataTransfer.set(REWRITE_RULE_DRAG_MIME, new vscode.DataTransferItem(JSON.stringify(payload)));
        this.onDragStartCallback?.(payload);
    }

    handleDrop(_target: RewriteRuleTreeNode | undefined, _dataTransfer: vscode.DataTransfer, _token: vscode.CancellationToken): void {}
}

class RewriteRuleSetItem extends vscode.TreeItem {
    constructor(
        public readonly label: string,
        public readonly rules: RewriteRuleItem[],
        public readonly setIndex: number
    ) {
        super(label, vscode.TreeItemCollapsibleState.Collapsed);
        this.contextValue = rules.length > 0 ? 'rewriteRuleSet' : 'rewriteRuleSetEmpty';
        this.description = `${rules.length} rule${rules.length === 1 ? '' : 's'}`;
    }
}

class RewriteRuleItem extends vscode.TreeItem {
    constructor(
        public readonly label: string,
        public readonly leftPath: string,
        public readonly rightPath: string,
        public readonly setLabel: string,
        public readonly setIndex: number
    ) {
        super(label, vscode.TreeItemCollapsibleState.None);
        this.contextValue = 'rewriteRule';
        this.tooltip = `left: ${leftPath}\nright: ${rightPath}`;
        this.command = {
            command: 'bigraph.editRewriteRule',
            title: 'Edit Rewrite Rule',
            arguments: [this]
        };
    }
}

class InitRulesItem extends vscode.TreeItem {
    constructor() {
        super('Init rules', vscode.TreeItemCollapsibleState.None);
        this.contextValue = 'rewriteRuleInit';
        this.iconPath = new vscode.ThemeIcon('add');
        this.command = {
            command: 'bigraph.initRewriteRulesConfig',
            title: 'Init rewrite rules config'
        };
    }
}

async function readWritableWorkspaceConfig(): Promise<{ uri?: vscode.Uri; config: { ruleSets: BigraphWorkspaceRuleSet[] } }> {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceRoot) {
        return { config: { ruleSets: [] } };
    }

    const uri = vscode.Uri.joinPath(workspaceRoot.uri, WORKSPACE_CONFIG_FILE);
    let rawText = '';
    try {
        rawText = Buffer.from(await vscode.workspace.fs.readFile(uri)).toString('utf8');
    } catch {
        // File does not exist; start with an empty config.
    }

    if (rawText.trim().length === 0) {
        return { uri, config: { ruleSets: [] } };
    }

    try {
        const parsed = JSON.parse(rawText) as BigraphWorkspaceFile;
        const rawRuleSets = Array.isArray(parsed.ruleSets)
            ? parsed.ruleSets
            : Array.isArray(parsed['rule sets'])
                ? parsed['rule sets']
                : [];
        const ruleSets = rawRuleSets
            .filter((ruleSet) => typeof ruleSet?.label === 'string' && Array.isArray(ruleSet.rules))
            .map((ruleSet) => ({
                label: ruleSet.label,
                rules: ruleSet.rules
                    .filter(
                        (rule) =>
                            typeof rule?.label === 'string' &&
                            typeof (rule.left ?? rule.leftFile) === 'string' &&
                            typeof (rule.right ?? rule.rightFile) === 'string'
                    )
                    .map((rule) => ({
                        label: rule.label,
                        left: rule.left ?? rule.leftFile ?? '',
                        right: rule.right ?? rule.rightFile ?? ''
                    }))
            }));
        return { uri, config: { ruleSets } };
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        vscode.window.showErrorMessage(`Cannot update ${WORKSPACE_CONFIG_FILE}: ${message}`);
        return { uri, config: { ruleSets: [] } };
    }
}

async function writeWorkspaceConfig(uri: vscode.Uri, config: { ruleSets: BigraphWorkspaceRuleSet[] }): Promise<void> {
    const json = JSON.stringify({ ruleSets: config.ruleSets }, null, 2) + '\n';
    await vscode.workspace.fs.writeFile(uri, Buffer.from(json, 'utf8'));
}
