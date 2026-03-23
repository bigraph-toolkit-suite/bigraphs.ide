import * as vscode from 'vscode';

export function registerEditorCommands(): vscode.Disposable[] {
    return [
        vscode.commands.registerCommand('bigraphide.helloWorld', () => {
            vscode.window.showInformationMessage('Hello World from Bigraphide!');
        }),
        vscode.commands.registerCommand('bigraph.toggleReadOnlyMode', async () => {
            const editorInit = await import('../editor/init.js');
            const enabled = editorInit.toggleReadOnlyMode();
            vscode.window.showInformationMessage(`Bigraph editor is now ${enabled ? 'read-only' : 'editable'}.`);
        }),
        vscode.commands.registerCommand('bigraph.testRewriteRule', async () => {
            try {
                const editorInit = await import('../editor/init.js');
                const glspConnector = editorInit.getGlspConnector();
                if (!glspConnector) {
                    vscode.window.showErrorMessage('GLSP connector not available. Please open a bigraph diagram first.');
                    return;
                }

                glspConnector.dispatchAction({
                    kind: 'bigraph.testRewriteRule'
                });

                vscode.window.showInformationMessage('Rewrite rule test triggered!');
            } catch (error) {
                vscode.window.showErrorMessage(`Failed to test rewrite rule: ${error}`);
            }
        })
    ];
}
