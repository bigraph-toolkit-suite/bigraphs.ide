import * as vscode from 'vscode';

export async function loadRewriteRuleEditorHtml(
    webview: vscode.Webview,
    extensionUri: vscode.Uri
): Promise<string> {
    const htmlUri = vscode.Uri.joinPath(extensionUri, 'src', 'rewriteRuleEditor', 'rewriteRuleEditor.html');
    const jsUri = vscode.Uri.joinPath(extensionUri, 'dist', 'rewrite-rule-editor.js');

    const [htmlData, jsData] = await Promise.all([
        vscode.workspace.fs.readFile(htmlUri),
        vscode.workspace.fs.readFile(jsUri)
    ]);

    const html = Buffer.from(htmlData).toString('utf8');
    const scriptContent = Buffer.from(jsData).toString('utf8');

    return html
        .replace(/\{\{CSP_SOURCE\}\}/g, webview.cspSource)
        .replace(/\{\{SCRIPT_CONTENT\}\}/g, scriptContent);
}
