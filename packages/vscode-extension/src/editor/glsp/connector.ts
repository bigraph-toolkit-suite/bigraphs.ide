import { GlspVscodeConnector } from "@eclipse-glsp/vscode-integration";
import { ReconnectingSocketGlspVscodeServer } from './reconnector';
import * as vscode from 'vscode';
import { getEvolutionManagerViewProvider } from '../../evolutionManager/evolutionManagerRegistry.js';
import { isEvolutionReadOnly, isManagedWorkspaceBigraphPath } from '../../workspaceBigraph/bigraphArtifacts.js';
import {
    clearSessionDirty,
    getSession,
    isManagedWorkspaceBigraph,
    setEvolutionRunningForFolder,
    setSessionDirty,
} from '../../workspaceBigraph/sessionRegistry.js';
import { reloadEvolutionDocumentFromDisk } from '../../evolutionManager/evolutionManagerJsonIO.js';
import {
    postWorkspaceBarState,
    postWorkspaceBarStateToAllPanels,
} from '../../workspaceBigraph/workspaceBigraphService.js';
import { getFsPathForClientId } from '../glspClientRegistry.js';
import {
    beginSessionSettling,
    consumePendingRunFolder,
    getGlspServer,
    isSessionReady,
    isSessionSettling,
    markSessionReady,
    sendActionToServer,
    setGlspServer,
    setPendingRunFolder,
    waitForClientSession,
} from '../glspServerBridge.js';

export {
    assumeSessionReady,
    getGlspServer,
    markSessionDisposed,
    sendActionToServer,
    setPendingRunFolder,
    waitForClientSession,
} from '../glspServerBridge.js';

export interface GlspConnection {
    connector: GlspVscodeConnector;
    server: ReconnectingSocketGlspVscodeServer;
}

let connectorReadOnlyMode = false;

export function setConnectorReadOnlyMode(enabled: boolean): void {
    connectorReadOnlyMode = enabled;
}

export async function connect(_context: vscode.ExtensionContext): Promise<GlspConnection> {
    const server = new ReconnectingSocketGlspVscodeServer({
        clientId: 'glsp.bigraph',
        clientName: 'bigraph',
        connectionOptions: {
            port: 52579,
        }
    });

    const glspVscodeConnector = new GlspVscodeConnector({
        server: server,
        logging: true,
        onBeforeReceiveMessageFromServer: (message, callback) => {
            const msg = message as { clientId?: string; action?: { kind?: string; severity?: string; operationId?: string; actionType?: string; reason?: string } };

            if (msg?.action?.kind === 'setModel' && msg.clientId) {
                markSessionReady(msg.clientId);
                const cid = msg.clientId;
                beginSessionSettling(cid, () => {
                    const fsPath = getFsPathForClientId(cid);
                    if (fsPath && isManagedWorkspaceBigraph(fsPath)) {
                        clearSessionDirty(cid);
                        postWorkspaceBarState(cid);
                    }
                });
            }

            if (msg?.action?.kind === 'setDirtyState' && msg.clientId) {
                const { reason } = msg.action as { reason?: string };
                if (reason !== 'save' && isManagedByClientId(msg.clientId)) {
                    return;
                }
            }

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
                const folder = consumePendingRunFolder();
                getEvolutionManagerViewProvider()?.postMessage({
                    type: 'operationStarted',
                    operationId: operationId ?? null,
                    actionType: actionType ?? null,
                    runFolder: folder ?? null
                });
                setEvolutionRunningForFolder(folder, true);
                postWorkspaceBarStateToAllPanels();
            } else if (msg?.action?.kind === 'bigraph.evolutionFinished') {
                const { operationId, reason } = msg.action;
                const provider = getEvolutionManagerViewProvider();
                provider?.postMessage({
                    type: 'operationFinished',
                    operationId: operationId ?? null,
                    reason: reason ?? 'completed'
                });
                if (provider) {
                    reloadEvolutionDocumentFromDisk(provider);
                }
                setEvolutionRunningForFolder(provider?.evolutionConfigPath ?? null, false);
                postWorkspaceBarStateToAllPanels();
            } else if (msg?.action?.kind === 'bigraph.verifyBigraphResult') {
                const action = msg.action as { kind: string; verificationId?: string; matched?: boolean; message?: string };
                getEvolutionManagerViewProvider()?.handleVerifyResult(
                    action.verificationId ?? '',
                    action.matched === true,
                    action.message ?? ''
                );
                return;
            }
            callback(message);
        },
        onBeforePropagateMessageToServer: (_originalMessage, processedMessage) => {
            trackWorkspaceDirtyFromClientMessage(processedMessage);
            const shouldBlock = connectorReadOnlyMode || isEvolutionReadOnlyMessage(processedMessage);
            if (shouldBlock && isMutatingClientMessage(processedMessage)) {
                return undefined;
            }
            return processedMessage;
        }
    });

    setGlspServer(server);

    return {
        connector: glspVscodeConnector,
        server
    };
}

function fsPathForMessage(message: unknown): string | undefined {
    if (!message || typeof message !== 'object') { return undefined; }
    const clientId = (message as Record<string, unknown>)['clientId'];
    if (typeof clientId !== 'string') { return undefined; }
    return getFsPathForClientId(clientId);
}

function isManagedByClientId(clientId: string): boolean {
    const fsPath = getFsPathForClientId(clientId);
    return !!fsPath && isManagedWorkspaceBigraphPath(fsPath);
}

function isEvolutionReadOnlyMessage(message: unknown): boolean {
    const fsPath = fsPathForMessage(message);
    return !!fsPath && isEvolutionReadOnly(fsPath);
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
        normalizedKind.includes('edit') ||
        normalizedKind.includes('compose') ||
        normalizedKind.includes('rename')
    );
}

const ALIGNMENT_OPERATION_KINDS = new Set([
    'changebounds',
    'changeroutingpoints',
    'layout',
    'bigraph.autolayout',
]);

const STRUCTURE_OPERATION_KINDS = new Set([
    'changecontainer',
    'createnode',
    'createedge',
    'createconnection',
    'delete',
    'deleteelement',
    'connect',
    'reconnect',
    'disconnect',
    'cut',
    'paste',
    'applylabeedit',
    'triggernodecreation',
    'triggeredgecreation',
    'bigraph.compose',
    'bigraph.create',
    'bigraph.createcontrol',
    'bigraph.filedropped',
]);

function isStructureOperationKind(kind: string): boolean {
    return STRUCTURE_OPERATION_KINDS.has(kind.toLowerCase());
}

function isAlignmentOperationKind(kind: string): boolean {
    return ALIGNMENT_OPERATION_KINDS.has(kind.toLowerCase());
}

function classifySingleAction(action: Record<string, unknown>): 'alignment' | 'structure' | null {
    const kind = action['kind'];
    if (typeof kind !== 'string') { return null; }

    const isOp = action['isOperation'] === true;
    const isCustomAlignment = kind.toLowerCase() === 'bigraph.autolayout';

    if (!isOp && !isCustomAlignment) { return null; }

    const k = kind.toLowerCase();
    if (isStructureOperationKind(k)) { return 'structure'; }
    if (isAlignmentOperationKind(k)) { return 'alignment'; }
    return 'structure';
}

function classifyWorkspaceDirtyKind(message: unknown): 'alignment' | 'structure' | null {
    if (!message || typeof message !== 'object') { return null; }
    const action = (message as Record<string, unknown>)['action'];
    if (!action || typeof action !== 'object') { return null; }
    const act = action as Record<string, unknown>;

    if (act['kind'] === 'compound' && Array.isArray(act['operationList'])) {
        const kinds: Array<'alignment' | 'structure'> = [];
        for (const op of act['operationList'] as unknown[]) {
            if (!op || typeof op !== 'object') { continue; }
            const cls = classifySingleAction(op as Record<string, unknown>);
            if (cls) { kinds.push(cls); }
        }
        if (!kinds.length) { return null; }
        if (kinds.some((k) => k === 'structure')) { return 'structure'; }
        return 'alignment';
    }

    return classifySingleAction(act);
}

function trackWorkspaceDirtyFromClientMessage(message: unknown): void {
    if (!message || typeof message !== 'object') { return; }
    const clientId = (message as Record<string, unknown>)['clientId'];
    if (typeof clientId !== 'string') { return; }
    if (!isSessionReady(clientId)) { return; }
    if (isSessionSettling(clientId)) { return; }

    const fsPath = getFsPathForClientId(clientId);
    if (!fsPath) { return; }

    if (!isManagedWorkspaceBigraph(fsPath)) { return; }
    const dirtyKind = classifyWorkspaceDirtyKind(message);
    if (!dirtyKind) { return; }
    if (!getSession(clientId)) { return; }
    if (dirtyKind === 'alignment') {
        setSessionDirty(clientId, { alignmentDirty: true });
    } else {
        setSessionDirty(clientId, { structureDirty: true });
    }
    postWorkspaceBarState(clientId);
}
