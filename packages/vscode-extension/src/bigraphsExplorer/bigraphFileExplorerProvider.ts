import * as vscode from 'vscode';
import * as path from 'path';
import { getDragService } from '../dragging';
import { EVOLUTION_FOLDER_SUFFIX } from '../evolutionManager/evolutionConstants.js';

function logDnd(_scope: string, _event: string, _details?: unknown): void {
    // DnD debug channel removed intentionally.
}

// Mime type for Bigraph Explorer drag and drop
const BIGRAPH_EXPLORER_MIME_TYPE = 'application/vnd.code.tree.bigraphexplorer';

type BigraphExplorerItem = BigraphFolderItem | BigraphFileItem;

/** A node in the virtual filesystem tree built from all qualifying .xmi files. */
interface TreeNode {
    folders: Map<string, TreeNode>;
    files: vscode.Uri[];
}

function makeNode(): TreeNode {
    return { folders: new Map(), files: [] };
}

export class BigraphExplorerProvider implements vscode.TreeDataProvider<BigraphExplorerItem> {
    private _onDidChangeTreeData: vscode.EventEmitter<BigraphExplorerItem | undefined | null | void> = new vscode.EventEmitter<BigraphExplorerItem | undefined | null | void>();
    readonly onDidChangeTreeData: vscode.Event<BigraphExplorerItem | undefined | null | void> = this._onDidChangeTreeData.event;

    /** Root of the virtual tree, rebuilt on every refresh. */
    private tree: TreeNode | null = null;

    constructor() {
        vscode.workspace.onDidCreateFiles(() => this.refresh());
        vscode.workspace.onDidDeleteFiles(() => this.refresh());
        vscode.workspace.onDidRenameFiles(() => this.refresh());
    }

    refresh(): void {
        this.tree = null;
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: BigraphExplorerItem): vscode.TreeItem {
        return element;
    }

    async getChildren(element?: BigraphExplorerItem): Promise<BigraphExplorerItem[]> {
        if (element instanceof BigraphFileItem) {
            return [];
        }

        const root = await this.getTree();
        const node = element instanceof BigraphFolderItem ? element.node : root;
        return this.nodeToItems(node);
    }

    private nodeToItems(node: TreeNode): BigraphExplorerItem[] {
        const folders: BigraphFolderItem[] = [...node.folders.entries()]
            .filter(([, child]) => this.nodeHasBigraphs(child))
            .map(([name, child]) => new BigraphFolderItem(name, child))
            .sort((a, b) => a.name.localeCompare(b.name));

        const files: BigraphFileItem[] = node.files
            .map(uri => new BigraphFileItem(path.basename(uri.fsPath, '.xmi'), uri))
            .sort((a, b) => String(a.label).localeCompare(String(b.label)));

        // Folders first, then files — same as VS Code's file explorer
        return [...folders, ...files];
    }

    private nodeHasBigraphs(node: TreeNode): boolean {
        if (node.files.length > 0) {
            return true;
        }
        return [...node.folders.values()].some((child) => this.nodeHasBigraphs(child));
    }

    private async getTree(): Promise<TreeNode> {
        if (this.tree) { return this.tree; }

        const wsRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const uris = await vscode.workspace.findFiles('**/*.xmi');
        const filtered = uris
            .filter(uri => !uri.fsPath.endsWith('.signature.xmi'))
            .filter(uri => !uri.fsPath.split(path.sep).some(seg => seg.endsWith(EVOLUTION_FOLDER_SUFFIX)));

        const root = makeNode();

        for (const uri of filtered) {
            const rel = path.relative(wsRoot, uri.fsPath);
            const parts = rel.split(path.sep);
            let cur = root;
            // Walk all directory segments, creating nodes as needed
            for (let i = 0; i < parts.length - 1; i++) {
                const seg = parts[i];
                if (!cur.folders.has(seg)) {
                    cur.folders.set(seg, makeNode());
                }
                cur = cur.folders.get(seg)!;
            }
            cur.files.push(uri);
        }

        this.tree = root;
        return root;
    }
}

export class BigraphExplorerDragAndDropController implements vscode.TreeDragAndDropController<BigraphExplorerItem> {
    dragMimeTypes = [BIGRAPH_EXPLORER_MIME_TYPE];
    dropMimeTypes: string[] = [];

    handleDrag(source: BigraphExplorerItem[], dataTransfer: vscode.DataTransfer, token: vscode.CancellationToken): void | Thenable<void> {
        const dragService = getDragService();
        // Only file items are draggable; ignore folder items
        const fileItems = source.filter((s): s is BigraphFileItem => s instanceof BigraphFileItem);
        if (fileItems.length === 0) {
            logDnd('explorer.bigraph', 'handleDrag-ignored-no-files');
            return;
        }

        const items = fileItems.map(item => ({
            label: String(item.label),
            uri: item.uri.toString(),
            fsPath: item.uri.fsPath,
            relativePath: vscode.workspace.asRelativePath(item.uri)
        }));

        // Only set custom MIME type -- NOT text/uri-list, because VS Code's
        // built-in editor drop handler would open the file in a new tab.
        dataTransfer.set(BIGRAPH_EXPLORER_MIME_TYPE, new vscode.DataTransferItem(JSON.stringify(items)));

        logDnd('explorer.bigraph', 'handleDrag-payload', { count: items.length, labels: items.map(i => i.label) });
        dragService.startSession('bigraphFiles', items);
        token.onCancellationRequested(() => {
            logDnd('explorer.bigraph', 'drag-session-cancelled');
            dragService.markSourceEnded();
        });
    }

    handleDrop(_target: BigraphExplorerItem | undefined, _dataTransfer: vscode.DataTransfer, _token: vscode.CancellationToken): void | Thenable<void> {
        // Drops into the tree itself are not handled
    }
}

class BigraphFolderItem extends vscode.TreeItem {
    constructor(
        public readonly name: string,
        public readonly node: TreeNode
    ) {
        super(name, vscode.TreeItemCollapsibleState.Expanded);
        this.iconPath = vscode.ThemeIcon.Folder;
        this.contextValue = 'bigraphFolder';
    }
}

class BigraphFileItem extends vscode.TreeItem {
    constructor(
        public readonly label: string,
        public readonly uri: vscode.Uri
    ) {
        super(label, vscode.TreeItemCollapsibleState.None);
        this.tooltip = this.uri.fsPath;
        this.command = {
            command: 'vscode.open',
            title: 'Open Bigraph',
            arguments: [this.uri]
        };
        this.contextValue = 'bigraphFile';
    }
}
