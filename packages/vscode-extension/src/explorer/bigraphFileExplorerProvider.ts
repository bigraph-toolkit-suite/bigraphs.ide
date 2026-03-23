import * as vscode from 'vscode';
import * as path from 'path';

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
            .map(([name, child]) => new BigraphFolderItem(name, child))
            .sort((a, b) => a.name.localeCompare(b.name));

        const files: BigraphFileItem[] = node.files
            .map(uri => new BigraphFileItem(path.basename(uri.fsPath, '.xmi'), uri))
            .sort((a, b) => String(a.label).localeCompare(String(b.label)));

        // Folders first, then files — same as VS Code's file explorer
        return [...folders, ...files];
    }

    private async getTree(): Promise<TreeNode> {
        if (this.tree) { return this.tree; }

        const wsRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const uris = await vscode.workspace.findFiles('**/*.xmi');
        const filtered = uris
            .filter(uri => !uri.fsPath.endsWith('.signature.xmi'))
            .filter(uri => !uri.fsPath.split(path.sep).some(seg => seg.endsWith('.evolution')));

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

/**
 * Drag and drop controller for Bigraph Explorer.
 * Sets both custom and standard MIME types, and notifies the GLSP webview
 * via a callback so it can receive the dragged file info through postMessage
 * (HTML5 DataTransfer data is not reliably forwarded across the webview boundary).
 */
/** Pending drop stash — set by handleDrag, consumed by whoever claims it within the timeout. */
let _pendingDropFiles: { label: string; uri: string; fsPath: string; relativePath: string }[] = [];
let _pendingDropTimer: ReturnType<typeof setTimeout> | null = null;

export function consumePendingDropFiles(): { label: string; uri: string; fsPath: string; relativePath: string }[] {
    const files = _pendingDropFiles;
    _pendingDropFiles = [];
    if (_pendingDropTimer) { clearTimeout(_pendingDropTimer); _pendingDropTimer = null; }
    return files;
}

export class BigraphExplorerDragAndDropController implements vscode.TreeDragAndDropController<BigraphExplorerItem> {
    dragMimeTypes = [BIGRAPH_EXPLORER_MIME_TYPE];
    dropMimeTypes: string[] = [];

    private onDragStartCallback?: (items: { label: string; uri: string; fsPath: string; relativePath: string }[]) => void;

    setOnDragStart(callback: (items: { label: string; uri: string; fsPath: string; relativePath: string }[]) => void): void {
        this.onDragStartCallback = callback;
    }

    handleDrag(source: BigraphExplorerItem[], dataTransfer: vscode.DataTransfer, _token: vscode.CancellationToken): void | Thenable<void> {
        // Only file items are draggable; ignore folder items
        const fileItems = source.filter((s): s is BigraphFileItem => s instanceof BigraphFileItem);
        if (fileItems.length === 0) { return; }

        const items = fileItems.map(item => ({
            label: String(item.label),
            uri: item.uri.toString(),
            fsPath: item.uri.fsPath,
            relativePath: vscode.workspace.asRelativePath(item.uri)
        }));

        // Only set custom MIME type -- NOT text/uri-list, because VS Code's
        // built-in editor drop handler would open the file in a new tab.
        dataTransfer.set(BIGRAPH_EXPLORER_MIME_TYPE, new vscode.DataTransferItem(JSON.stringify(items)));

        // Stash files so a webview drop handler can claim them via consumePendingDropFiles()
        _pendingDropFiles = items;
        if (_pendingDropTimer) { clearTimeout(_pendingDropTimer); }
        _pendingDropTimer = setTimeout(() => { _pendingDropFiles = []; _pendingDropTimer = null; }, 10000);

        if (this.onDragStartCallback) {
            this.onDragStartCallback(items);
        }
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
