import { configureDefaultCommands, NavigateAction } from '@eclipse-glsp/vscode-integration';
import * as path from 'path';
import * as vscode from 'vscode';
import EditorProvider from './editorProvider';
import {
    connect,
    GlspConnection,
    getGlspServer,
    setConnectorReadOnlyMode,
    sendActionToServer
} from './glsp/connector';
import { GlspReconnector } from './glsp/reconnector';
import { SessionRegistry } from '../assistantIntegration/session-registry';
import { BigraphFacade } from '../assistantIntegration/bigraph-facade';
import { registerBigraphTools } from '../assistantIntegration/tools';
import { McpApiServer } from '../assistantIntegration/mcp-api-server';
import { ensureCursorMCPConfig } from '../assistantIntegration/cursor-config';
import { withUtilityServerSession } from './glsp/utility-server-session';

let globalGlspConnector: any = null;
let readOnlyModeEnabled = false;

export function getGlspConnector() {
    return globalGlspConnector;
}

export function isReadOnlyModeEnabled(): boolean {
    return readOnlyModeEnabled;
}

export function setReadOnlyMode(enabled: boolean): boolean {
    readOnlyModeEnabled = enabled;
    setConnectorReadOnlyMode(enabled);
    EditorProvider.setReadOnlyMode(enabled);
    return readOnlyModeEnabled;
}

export function toggleReadOnlyMode(): boolean {
    return setReadOnlyMode(!readOnlyModeEnabled);
}

/**
 * Sends a custom action directly to the GLSP server for the active editor session.
 * Uses the server's send-emitter directly so custom action kinds (e.g. bigraph.compose)
 * are not filtered out by the client-action-list check inside dispatchAction.
 *
 * @param action  A plain action object with at least a `kind` string property.
 */
export function dispatchActionToActiveEditor(action: Record<string, unknown>): void {
    const sent = sendActionToServer(action);
    if (!sent) {
        vscode.window.showWarningMessage('No active bigraph editor found. Open a .xmi file first.');
    }
}

export async function dispatchActionInUtilitySession(
    action: Record<string, unknown>,
    waitForCompletion?: () => Promise<boolean>
): Promise<boolean> {
    const result = await withUtilityServerSession(getGlspServer(), async session => {
        session.dispatch(action);
        return waitForCompletion ? await waitForCompletion() : true;
    }, {
        diagramType: 'bigraph-xmi'
    });

    if (result === undefined) {
        vscode.window.showWarningMessage('Bigraph backend is not ready yet. Try again in a moment.');
        return false;
    }
    return result;
}

export async function initDiagramEditor(context: vscode.ExtensionContext) {

    const connection: GlspConnection = await connect(context);
    console.log("connection",connection);
    

    const glspVscodeConnector = connection.connector;
    const bigraphServer = connection.server;

    // Store global reference for use by other modules
    globalGlspConnector = glspVscodeConnector;

    const sessionRegistry = new SessionRegistry();
    const bigraphFacade = new BigraphFacade(sessionRegistry);

    if (typeof vscode.lm?.registerTool === 'function') {
        try {
            const toolDisposables = registerBigraphTools(context, bigraphFacade);
            context.subscriptions.push(...toolDisposables);
            console.log('[bigraphide] vscode.lm tools registered');
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            console.warn(`[bigraphide] vscode.lm tool registration failed (harmless in Cursor when using MCP): ${msg}`);
        }
    }

    const storagePath = context.globalStorageUri.fsPath;
    const mcpApiServer = new McpApiServer(bigraphFacade, storagePath);
    try {
        const port = await mcpApiServer.start();
        console.log(`[bigraphide] MCP API server started on port ${port}`);
        const portFilePath = path.join(storagePath, 'mcp-api-port');
        ensureCursorMCPConfig(context, portFilePath);
        context.subscriptions.push({
            dispose: () => mcpApiServer.stop(),
        });
    } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        console.warn(`[bigraphide] MCP API server failed to start: ${message}`);
    }

    const customEditorProvider = vscode.window.registerCustomEditorProvider(
        'bigraph.glspDiagram',
        new EditorProvider(context, glspVscodeConnector, sessionRegistry, bigraphFacade),
        {
            webviewOptions: { retainContextWhenHidden: true },
            supportsMultipleEditorsPerDocument: false
        }
    );

    context.subscriptions.push(bigraphServer, glspVscodeConnector, customEditorProvider);

    // Auto-reconnect is active in development mode (launched via --extensionDevelopmentPath).
    let reconnector: GlspReconnector | undefined;
    if (context.extensionMode === vscode.ExtensionMode.Development) {
        const serverPort = parseInt(process.env.GLSP_SERVER_PORT || '52579', 10);
        const serverHost = process.env.GLSP_SERVER_HOST || 'localhost';
        reconnector = new GlspReconnector(
            bigraphServer,
            glspVscodeConnector as any,
            serverPort,
            serverHost,
        );
        context.subscriptions.push(reconnector);
    }

    // Catch initial startup failures so they don't become unhandled rejections.
    // In dev mode the reconnector will keep retrying until the backend is up.
    bigraphServer.start().catch((err: unknown) => {
        console.warn('[BigraphIDE] Initial GLSP server connection failed:', err);
        reconnector?.notifyInitialConnectionFailed();
    });

    // Suppress expected "client not ready" rejections that fire during disconnect
    // when the GLSP library tries to call disposeClientSession on a dead connection.
    // These are harmless — the server session cleans up on its own when the socket closes.
    if (context.extensionMode === vscode.ExtensionMode.Development) {
        process.on('unhandledRejection', (reason: unknown) => {
            const msg = reason instanceof Error ? reason.message : String(reason);
            if (msg.includes('JsonrpcGLSPClient is not ready') || msg.includes('Connection is disposed')) {
                return; // swallow — expected during backend restart
            }
            // Re-emit as an uncaught exception for anything else.
            console.error('[BigraphIDE] Unhandled rejection:', reason);
        });
    }

    console.log("Started");

    configureDefaultCommands({ extensionContext: context, connector: glspVscodeConnector, diagramPrefix: 'bigraph' });

    context.subscriptions.push(
        vscode.commands.registerCommand('bigraph.goToNextNode', () => {
            glspVscodeConnector.dispatchAction(NavigateAction.create('next'));
        }),
        vscode.commands.registerCommand('bigraph.goToPreviousNode', () => {
            glspVscodeConnector.dispatchAction(NavigateAction.create('previous'));
        }),
        vscode.commands.registerCommand('bigraph.showDocumentation', () => {
            glspVscodeConnector.dispatchAction(NavigateAction.create('documentation'));
        })
    );
}