import * as vscode from 'vscode';
import * as path from 'path';
import { getDragService } from '../dragging';
import { EVOLUTION_FOLDER_SUFFIX } from '../evolutionManager/evolutionConstants.js';

function logDnd(_scope: string, _event: string, _details?: unknown): void {
    // DnD debug channel removed intentionally.
}

// Mime type for Bigraph Explorer drag and drop
const BIGRAPH_EXPLORER_MIME_TYPE = 'application/vnd.code.tree.bigraphexplorer';

export type BigraphExplorerItem = BigraphFolderItem | BigraphFileItem;

/** A node in the filesystem tree: all folders (incl. empty) plus qualifying .xmi files. */
interface TreeNode {
    folders: Map<string, TreeNode>;
    files: vscode.Uri[];
}

function makeNode(): TreeNode {
    return { folders: new Map(), files: [] };
}

function shouldSkipFolder(name: string): boolean {
    if (name.startsWith('.')) {
        return true;
    }
    if (name === 'node_modules') {
        return true;
    }
    if (name.endsWith(EVOLUTION_FOLDER_SUFFIX)) {
        return true;
    }
    return false;
}

function isBigraphXmiFile(name: string): boolean {
    const lower = name.toLowerCase();
    return lower.endsWith('.xmi') && !lower.endsWith('.signature.xmi');
}

/**
 * Folder to create a new bigraph in: selected folder, parent of selected file, or workspace root.
 */
export function resolveCreateTargetFolder(
    selection: readonly BigraphExplorerItem[] | undefined,
    workspaceRoot: vscode.Uri
): vscode.Uri {
    const item = selection?.[0];
    if (item instanceof BigraphFolderItem) {
        return item.uri;
    }
    if (item instanceof BigraphFileItem) {
        return vscode.Uri.joinPath(item.uri, '..');
    }
    return workspaceRoot;
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

        // File create/delete events miss empty-folder mkdir/rmdir; watch the FS too.
        const watcher = vscode.workspace.createFileSystemWatcher('**/*');
        watcher.onDidCreate(async uri => {
            try {
                const stat = await vscode.workspace.fs.stat(uri);
                if ((stat.type & vscode.FileType.Directory) !== 0) {
                    this.refresh();
                }
            } catch {
                // Created then removed before we could stat — ignore.
            }
        });
        watcher.onDidDelete(() => this.refresh());
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

        const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri;
        if (!workspaceRoot) {
            return [];
        }

        const root = await this.getTree();
        const node = element instanceof BigraphFolderItem ? element.node : root;
        const parentUri = element instanceof BigraphFolderItem ? element.uri : workspaceRoot;
        return this.nodeToItems(node, parentUri);
    }

    private nodeToItems(node: TreeNode, parentUri: vscode.Uri): BigraphExplorerItem[] {
        const folders: BigraphFolderItem[] = [...node.folders.entries()]
            .map(([name, child]) => {
                const uri = vscode.Uri.joinPath(parentUri, name);
                const hasChildren = child.folders.size > 0 || child.files.length > 0;
                return new BigraphFolderItem(
                    name,
                    child,
                    uri,
                    hasChildren
                        ? vscode.TreeItemCollapsibleState.Expanded
                        : vscode.TreeItemCollapsibleState.Collapsed
                );
            })
            .sort((a, b) => a.name.localeCompare(b.name));

        const files: BigraphFileItem[] = node.files
            .map(uri => new BigraphFileItem(path.basename(uri.fsPath, '.xmi'), uri))
            .sort((a, b) => String(a.label).localeCompare(String(b.label)));

        // Folders first, then files — same as VS Code's file explorer
        return [...folders, ...files];
    }

    private async getTree(): Promise<TreeNode> {
        if (this.tree) { return this.tree; }

        const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri;
        this.tree = workspaceRoot ? await this.buildTreeFromFs(workspaceRoot) : makeNode();
        return this.tree;
    }

    private async buildTreeFromFs(dirUri: vscode.Uri): Promise<TreeNode> {
        const node = makeNode();
        let entries: [string, vscode.FileType][];
        try {
            entries = await vscode.workspace.fs.readDirectory(dirUri);
        } catch {
            return node;
        }

        for (const [name, type] of entries) {
            if ((type & vscode.FileType.Directory) !== 0) {
                if (shouldSkipFolder(name)) {
                    continue;
                }
                node.folders.set(name, await this.buildTreeFromFs(vscode.Uri.joinPath(dirUri, name)));
            } else if ((type & vscode.FileType.File) !== 0 && isBigraphXmiFile(name)) {
                node.files.push(vscode.Uri.joinPath(dirUri, name));
            }
        }

        return node;
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

export class BigraphFolderItem extends vscode.TreeItem {
    constructor(
        public readonly name: string,
        public readonly node: TreeNode,
        public readonly uri: vscode.Uri,
        collapsibleState: vscode.TreeItemCollapsibleState = vscode.TreeItemCollapsibleState.Expanded
    ) {
        super(name, collapsibleState);
        this.iconPath = vscode.ThemeIcon.Folder;
        this.contextValue = 'bigraphFolder';
        this.tooltip = this.uri.fsPath;
        this.resourceUri = this.uri;
    }
}

export class BigraphFileItem extends vscode.TreeItem {
    constructor(
        public readonly label: string,
        public readonly uri: vscode.Uri
    ) {
        super(label, vscode.TreeItemCollapsibleState.None);
        this.tooltip = this.uri.fsPath;
        this.resourceUri = this.uri;
        this.command = {
            command: 'vscode.open',
            title: 'Open Bigraph',
            arguments: [this.uri]
        };
        this.contextValue = 'bigraphFile';
    }
}
