// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
import { registerCreateBigraphCommand } from './commands/create-bigraph';
import { registerCreateFolderCommand } from './commands/create-folder';
import { registerEditorCommands } from './commands/editor-commands';
import { initExplorerSidebar } from './bigraphsExplorer/init';
import { HtmlViewProvider } from './rewriteRuleEditor/htmlViewProvider';
import { initEvolutionSidebar } from './evolutionManager/init';

async function initEditor(context: vscode.ExtensionContext): Promise<void> {
	await (await import('./editor/init.js')).initDiagramEditor(context);
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
	console.log('Congratulations, your extension "bigraphide" is now active!');
	await vscode.commands.executeCommand('setContext', 'bigraph.showRewriteRuleEditor', false);

	// Register sidebar views first (no editor/GLSP load); keep in subscriptions
	context.subscriptions.push(...initExplorerSidebar());
	const htmlViewProvider = new HtmlViewProvider(context.extensionUri);
	context.subscriptions.push(
		vscode.window.registerWebviewViewProvider('bigraphHtmlView', htmlViewProvider)
	);
	const evolutionDisposables = initEvolutionSidebar(context);
	context.subscriptions.push(...evolutionDisposables);

	const editorReady = initEditor(context).catch((err) => {
		console.error('[bigraphide] Editor/GLSP init failed:', err);
		throw err;
	});

	context.subscriptions.push(...registerEditorCommands());
	context.subscriptions.push(registerCreateBigraphCommand(editorReady));
	context.subscriptions.push(registerCreateFolderCommand());
}

// This method is called when your extension is deactivated
export function deactivate() {
	
}
