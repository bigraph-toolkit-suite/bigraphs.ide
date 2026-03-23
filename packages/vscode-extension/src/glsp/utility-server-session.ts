import { ReconnectingSocketGlspVscodeServer } from './reconnector';

export interface UtilityServerSession {
    clientId: string;
    dispatch(action: Record<string, unknown>): void;
}

export interface UtilityServerSessionOptions {
    diagramType: string;
    clientActionKinds?: string[];
    clientId?: string;
}

type GlspClientSessionBridge = {
    initializeClientSession(params: {
        clientSessionId: string;
        diagramType: string;
        clientActionKinds: string[];
    }): Promise<void>;
};

type ServerMessageEmitter = {
    onSendToServerEmitter: {
        fire(message: unknown): void;
    };
};

const DEFAULT_UTILITY_CLIENT_ACTION_KINDS = ['message'];
const DEFAULT_UTILITY_CLIENT_ID = 'glsp.bigraph.extension';

export async function withUtilityServerSession<T>(
    server: ReconnectingSocketGlspVscodeServer | null,
    callback: (session: UtilityServerSession) => Promise<T>,
    options: UtilityServerSessionOptions
): Promise<T | undefined> {
    if (!server) {
        console.warn('[BigraphIDE] withUtilityServerSession: server not yet initialised');
        return undefined;
    }

    const utilityServer = server as ReconnectingSocketGlspVscodeServer & ServerMessageEmitter & {
        glspClient: Promise<GlspClientSessionBridge>;
    };
    const clientId = options.clientId ?? DEFAULT_UTILITY_CLIENT_ID;

    try {
        const glspClient = await utilityServer.glspClient;
        await glspClient.initializeClientSession({
            clientSessionId: clientId,
            diagramType: options.diagramType,
            clientActionKinds: options.clientActionKinds ?? DEFAULT_UTILITY_CLIENT_ACTION_KINDS
        });

        return await callback({
            clientId,
            dispatch(action: Record<string, unknown>): void {
                utilityServer.onSendToServerEmitter.fire({ clientId, action });
            }
        });
    } catch (error) {
        console.warn('[BigraphIDE] Failed to use utility GLSP session:', error);
        return undefined;
    }
}
