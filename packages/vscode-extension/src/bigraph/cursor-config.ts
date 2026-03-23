import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Generates or updates .cursor/mcp.json in the workspace root
 * so Cursor knows how to spawn the bigraph MCP server.
 */
export function ensureCursorMCPConfig(
    context: vscode.ExtensionContext,
    portFilePath: string
): void {
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!workspaceFolder) {
        console.warn('[bigraphide] No workspace folder – cannot write .cursor/mcp.json');
        return;
    }

    const cursorDir = path.join(workspaceFolder, '.cursor');
    const mcpConfigPath = path.join(cursorDir, 'mcp.json');

    const mcpEntryScript = path.join(context.extensionPath, 'dist', 'mcp-stdio-entry.js');

    if (!fs.existsSync(mcpEntryScript)) {
        console.warn(`[bigraphide] MCP entry script not found at ${mcpEntryScript}`);
        return;
    }

    const bigraphServerConfig = {
        command: 'node',
        args: [mcpEntryScript],
        env: {
            BIGRAPH_MCP_PORT_FILE: portFilePath,
        },
    };

    let existingConfig: { mcpServers?: Record<string, unknown> } = { mcpServers: {} };
    if (fs.existsSync(mcpConfigPath)) {
        try {
            const raw = fs.readFileSync(mcpConfigPath, 'utf-8');
            existingConfig = JSON.parse(raw);
            if (!existingConfig.mcpServers) {
                existingConfig.mcpServers = {};
            }
        } catch {
            existingConfig = { mcpServers: {} };
        }
    }

    const existing = existingConfig.mcpServers?.['bigraph-editor'] as { command?: string; args?: string[]; env?: { BIGRAPH_MCP_PORT_FILE?: string } } | undefined;
    if (
        existing &&
        existing.command === bigraphServerConfig.command &&
        JSON.stringify(existing.args) === JSON.stringify(bigraphServerConfig.args) &&
        existing.env?.BIGRAPH_MCP_PORT_FILE === portFilePath
    ) {
        return;
    }

    existingConfig.mcpServers = existingConfig.mcpServers ?? {};
    existingConfig.mcpServers['bigraph-editor'] = bigraphServerConfig;

    if (!fs.existsSync(cursorDir)) {
        fs.mkdirSync(cursorDir, { recursive: true });
    }

    fs.writeFileSync(mcpConfigPath, JSON.stringify(existingConfig, null, 2));

    console.log(`[bigraphide] Cursor MCP config written to ${mcpConfigPath}`);
    vscode.window.showInformationMessage(
        'BigraphIDE: Cursor MCP configuration updated. ' +
        'Restart Cursor or reload the MCP server list to activate bigraph agent tools.'
    );
}
