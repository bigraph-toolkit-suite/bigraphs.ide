// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
import { registerCreateBigraphCommand } from './commands/create-bigraph';
import { registerEditorCommands } from './commands/editor-commands';
import { initExplorerSidebar, setExplorerDragStartNotifier } from './explorer/init';
import { HtmlViewProvider } from './explorer/htmlViewProvider';
import { initEvolutionSidebar } from './evolution/init';

async function initEditor(context: vscode.ExtensionContext): Promise<void> {
	const editorInit = await import('./editor/init.js');
	setExplorerDragStartNotifier(editorInit.sendMessageToWebview);
	await editorInit.initDiagramEditor(context);
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
	console.log('Congratulations, your extension "bigraphide" is now active!');
	await vscode.commands.executeCommand('setContext', 'bigraph.showRewriteRuleEditor', false);

	// Register sidebar views first (no editor/GLSP load); keep in subscriptions
	context.subscriptions.push(...initExplorerSidebar());
	const htmlViewProvider = new HtmlViewProvider();
	context.subscriptions.push(
		vscode.window.registerWebviewViewProvider('bigraphHtmlView', htmlViewProvider)
	);
	setExplorerDragStartNotifier((msg) => htmlViewProvider.postMessage(msg));
	const evolutionDisposables = initEvolutionSidebar(context);
	context.subscriptions.push(...evolutionDisposables);
	// Forward drag-start events to the Evolution Manager webview as well
	const { getEvolutionManagerViewProvider } = await import('./evolution/evolutionManagerViewProvider.js');
	setExplorerDragStartNotifier((msg) => getEvolutionManagerViewProvider()?.postMessage(msg as Record<string, unknown>));

	const editorReady = initEditor(context).catch((err) => {
		console.error('[bigraphide] Editor/GLSP init failed:', err);
		throw err;
	});

	context.subscriptions.push(...registerEditorCommands());
	context.subscriptions.push(registerCreateBigraphCommand(editorReady));
}

// This method is called when your extension is deactivated
export function deactivate() {
	
}
