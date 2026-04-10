import { GlspVscodeConnector } from "@eclipse-glsp/vscode-integration";
import { ReconnectingSocketGlspVscodeServer } from './reconnector';
import * as path from 'path';
import * as vscode from 'vscode';
import EditorProvider from '../editorProvider';
import { getEvolutionManagerViewProvider } from '../../evolutionManager/evolutionManagerViewProvider';

/** Holds the runFolder for the most recently dispatched evolution action, used to enrich operationStarted. */
let pendingRunFolder: string | null = null;

export function setPendingRunFolder(folder: string): void {
    pendingRunFolder = folder;
}

export interface GlspConnection {
    connector: GlspVscodeConnector;
    server: ReconnectingSocketGlspVscodeServer;
}

let connectorReadOnlyMode = false;
let globalServer: ReconnectingSocketGlspVscodeServer | null = null;

export function setConnectorReadOnlyMode(enabled: boolean): void {
    connectorReadOnlyMode = enabled;
}

export function getGlspServer(): ReconnectingSocketGlspVscodeServer | null {
    return globalServer;
}

/**
 * Sends a custom action directly to the GLSP server for the active editor session.
 * This bypasses the client-action-list filter in {@code dispatchAction} and is the
 * correct way to fire server-only actions (e.g. bigraph.compose) from the extension host.
 *
 * @param action  Plain action object with at least a {@code kind} string property.
 * @returns true if the message was fired, false if no active client was found.
 */
export function sendActionToServer(action: Record<string, unknown>): boolean {
    const clientInfo = EditorProvider.getActiveClientInfo();
    if (!clientInfo) {
        console.warn('[BigraphIDE] sendActionToServer: no active client found');
        return false;
    }
    if (!globalServer) {
        console.warn('[BigraphIDE] sendActionToServer: server not yet initialised');
        return false;
    }
    const message = { clientId: clientInfo.clientId, action };
    (globalServer as unknown as { onSendToServerEmitter: { fire(msg: unknown): void } })
        .onSendToServerEmitter.fire(message);
    return true;
}

export async function connect(context: vscode.ExtensionContext): Promise<GlspConnection> {
    const serverPort = parseInt(process.env.GLSP_SERVER_PORT || '52579', 10); // Changed to match bigraph server
    const serverHost = process.env.GLSP_SERVER_HOST || 'localhost';
    
    // Create a socket server connector to your external Java server
    const additionalArgs: any = [];

    const server = new ReconnectingSocketGlspVscodeServer({
        clientId: 'glsp.bigraph', // Changed from workflow to bigraph
        clientName: 'bigraph',    // Changed from workflow to bigraph
        connectionOptions: {
            port: 52579,
            
        }
    });
    
    // Initialize GLSP-VSCode connector with server wrapper
    const glspVscodeConnector = new GlspVscodeConnector({
        server: server,
        logging: true,
        onBeforeReceiveMessageFromServer: (message, callback) => {
            const msg = message as { clientId?: string; action?: { kind?: string; severity?: string; operationId?: string; actionType?: string; reason?: string } };

            // When a WARNING or ERROR MessageAction arrives the connector handles it as a VS Code
            // toast and strips it from the pipeline (never forwarded to the webview). We therefore
            // send an additional custom ActionMessage directly to the webview so our palette-shake
            // handler can act on it.
            if (msg?.action?.kind === 'message') {
                const sev = msg.action.severity?.toUpperCase();
                if ((sev === 'WARNING' || sev === 'ERROR') && msg.clientId) {
                    const shakeMsg = { clientId: msg.clientId, action: { kind: 'bigraph.paletteShake', severity: sev } };
                    (glspVscodeConnector as unknown as { sendMessageToClient(id: string, msg: unknown): void })
                        .sendMessageToClient(msg.clientId, shakeMsg);
                }
            }

            if (msg?.action?.kind === 'bigraph.evolutionStarted') {
                const { operationId, actionType } = msg.action;
                const folder = pendingRunFolder;
                pendingRunFolder = null;
                getEvolutionManagerViewProvider()?.postMessage({
                    type: 'operationStarted',
                    operationId: operationId ?? null,
                    actionType: actionType ?? null,
                    runFolder: folder ?? null
                });
            } else if (msg?.action?.kind === 'bigraph.evolutionFinished') {
                const { operationId, reason } = msg.action;
                const provider = getEvolutionManagerViewProvider();
                provider?.postMessage({
                    type: 'operationFinished',
                    operationId: operationId ?? null,
                    reason: reason ?? 'completed'
                });
                provider?.refreshTreeFromJson();
            } else if (msg?.action?.kind === 'bigraph.verifyBigraphResult') {
                const action = msg.action as { kind: string; verificationId?: string; matched?: boolean; message?: string };
                getEvolutionManagerViewProvider()?.handleVerifyResult(
                    action.verificationId ?? '',
                    action.matched === true,
                    action.message ?? ''
                );
                // This action is extension-side only and has no GLSP client handler.
                // Consume it here to avoid "Missing handler for action" errors.
                return;
            }
            callback(message);
        },
        onBeforePropagateMessageToServer: (_originalMessage, processedMessage) => {
            // Determine whether this message should be blocked.
            // It is blocked when either:
            //   (a) the global read-only mode is active, or
            //   (b) the message originates from a panel whose file lives inside a
            //       *.evolution folder (except workspace-bigraph.xmi which must stay editable).
            const shouldBlock = connectorReadOnlyMode || isEvolutionReadOnlyMessage(processedMessage);
            if (shouldBlock && isMutatingClientMessage(processedMessage)) {
                return undefined;
            }
            return processedMessage;
        }
    });

    globalServer = server;

    return {
        connector: glspVscodeConnector,
        server
    };
}

/** Checks if a message comes from a GLSP panel whose file is inside a *.evolution folder
 *  (but not workspace-bigraph.xmi, which remains editable). */
function isEvolutionReadOnlyMessage(message: unknown): boolean {
    if (!message || typeof message !== 'object') { return false; }
    const clientId = (message as Record<string, unknown>)['clientId'];
    if (typeof clientId !== 'string') { return false; }

    const fsPath = EditorProvider.getFsPathForClientId(clientId);
    if (!fsPath) { return false; }

    const parts = fsPath.split(path.sep);
    if (!parts.some((seg: string) => seg.endsWith('.evolution'))) { return false; }
    return path.basename(fsPath) !== 'workspace-bigraph.xmi';
}

function isMutatingClientMessage(message: unknown): boolean {
    const seen = new Set<unknown>();
    return containsMutatingAction(message, seen);
}

function containsMutatingAction(value: unknown, seen: Set<unknown>): boolean {
    if (!value || typeof value !== 'object' || seen.has(value)) {
        return false;
    }
    seen.add(value);

    if (looksLikeMutatingAction(value)) {
        return true;
    }

    for (const nested of Object.values(value as Record<string, unknown>)) {
        if (containsMutatingAction(nested, seen)) {
            return true;
        }
    }
    return false;
}

function looksLikeMutatingAction(candidate: unknown): boolean {
    if (!candidate || typeof candidate !== 'object') {
        return false;
    }
    const action = candidate as { kind?: unknown; isOperation?: unknown };
    if (action.isOperation === true) {
        return true;
    }
    if (typeof action.kind !== 'string') {
        return false;
    }

    // Explicitly keep viewport/navigation style actions in read-only mode.
    if (action.kind === 'moveViewport' || action.kind === 'center' || action.kind === 'fit' || action.kind === 'select') {
        return false;
    }

    const normalizedKind = action.kind.toLowerCase();
    return (
        normalizedKind.includes('operation') ||
        normalizedKind.includes('triggernodecreation') ||
        normalizedKind.includes('triggeredgecreation') ||
        normalizedKind.includes('create') ||
        normalizedKind.includes('delete') ||
        normalizedKind.includes('change') ||
        normalizedKind.includes('layout') ||
        normalizedKind.includes('apply') ||
        normalizedKind.includes('edit')
    );
}
