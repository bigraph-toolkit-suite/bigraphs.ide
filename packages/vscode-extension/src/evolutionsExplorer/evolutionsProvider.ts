import * as vscode from 'vscode';
import { EVOLUTION_FOLDER_SUFFIX } from '../evolutionManager/evolutionConstants.js';

const EVOLUTION_SUFFIX = EVOLUTION_FOLDER_SUFFIX;

class EvolutionFolderItem extends vscode.TreeItem {
	constructor(
		public readonly folderPath: string,
		public readonly label: string
	) {
		super(label, vscode.TreeItemCollapsibleState.None);
		this.contextValue = 'evolutionFolder';
		this.tooltip = folderPath;
		this.command = {
			command: 'bigraph.selectEvolutionFolder',
			title: 'Open in Evolution Manager',
			arguments: [folderPath]
		};
	}
}

export class EvolutionsProvider implements vscode.TreeDataProvider<EvolutionFolderItem> {
	private readonly _onDidChangeTreeData = new vscode.EventEmitter<EvolutionFolderItem | undefined | null | void>();
	readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

	constructor() {
		vscode.workspace.onDidCreateFiles(() => this.refresh());
		vscode.workspace.onDidDeleteFiles(() => this.refresh());
		vscode.workspace.onDidRenameFiles(() => this.refresh());
	}

	refresh(): void {
		this._onDidChangeTreeData.fire();
	}

	getTreeItem(element: EvolutionFolderItem): vscode.TreeItem {
		return element;
	}

	async getChildren(): Promise<EvolutionFolderItem[]> {
		const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
		if (!workspaceRoot) {
			return [];
		}

		const folders: { path: string; name: string }[] = [];
		await this.collectEvolutionFolders(workspaceRoot.uri, '', folders);
		folders.sort((a, b) => a.name.localeCompare(b.name));

		return folders.map(
			(f) => new EvolutionFolderItem(f.path, f.name)
		);
	}

	private async collectEvolutionFolders(
		baseUri: vscode.Uri,
		relativePath: string,
		out: { path: string; name: string }[]
	): Promise<void> {
		let entries: [string, vscode.FileType][];
		try {
			entries = await vscode.workspace.fs.readDirectory(baseUri);
		} catch {
			return;
		}

		for (const [name, type] of entries) {
			if (type !== vscode.FileType.Directory) {
				continue;
			}
			if (name.endsWith(EVOLUTION_SUFFIX)) {
				const fullPath = relativePath ? `${relativePath}/${name}` : name;
				const folderPath = vscode.Uri.joinPath(vscode.workspace.workspaceFolders![0].uri, fullPath).fsPath;
				const label = name.slice(0, -EVOLUTION_SUFFIX.length);
				out.push({ path: folderPath, name: label });
			}
			const nextRelative = relativePath ? `${relativePath}/${name}` : name;
			const nextUri = vscode.Uri.joinPath(baseUri, name);
			await this.collectEvolutionFolders(nextUri, nextRelative, out);
		}
	}
}
