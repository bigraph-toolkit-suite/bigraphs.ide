import * as vscode from 'vscode';
import { SessionRegistry, BigraphSession } from './session-registry';

export class BigraphFacade {
    private pendingRequests = new Map<string, {
        resolve: (value: unknown) => void;
        reject: (reason: unknown) => void;
    }>();

    constructor(private registry: SessionRegistry) {}

    /**
     * Register the response listener on a webview panel.
     * Call this for every new panel in EditorProvider.
     */
    registerPanel(panel: vscode.WebviewPanel): vscode.Disposable {
        return panel.webview.onDidReceiveMessage((msg: unknown) => {
            const m = msg as { type?: string; requestId?: string; result?: unknown; error?: string };
            if (m && m.type === 'bigraph-bridge-response' && m.requestId) {
                const pending = this.pendingRequests.get(m.requestId);
                if (pending) {
                    this.pendingRequests.delete(m.requestId);
                    if (m.error) {
                        console.warn(`[bigraphide] Facade response ERROR for ${m.requestId}:`, m.error);
                        pending.reject(new Error(m.error));
                    } else {
                        console.log(`[bigraphide] Facade response OK for ${m.requestId}`);
                        pending.resolve(m.result);
                    }
                } else {
                    console.warn(`[bigraphide] Facade received response for unknown requestId: ${m.requestId}`);
                }
            }
        });
    }

    private resolveSession(filePath?: string): BigraphSession {
        if (filePath) {
            const session = this.registry.getByFilePath(filePath);
            if (!session) {
                throw new Error(
                    `No open bigraph editor for: ${filePath}. Open files: ${this.registry.getAllSessions().map(s => s.fileName).join(', ')}`
                );
            }
            return session;
        }
        const active = this.registry.getActive();
        if (!active) {
            throw new Error(
                'No active bigraph editor. Open files: ' +
                this.registry.getAllSessions().map(s => s.fileName).join(', ')
            );
        }
        return active;
    }

    private async query(method: string, params: Record<string, unknown> = {}, filePath?: string): Promise<unknown> {
        const session = this.resolveSession(filePath);
        const requestId = `${method}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        console.log(`[bigraphide] Facade query: ${method} -> clientId=${session.clientId}, file=${session.fileName}, requestId=${requestId}`);

        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pendingRequests.delete(requestId);
                const openFiles = this.registry.getAllSessions().map(s => s.fileName).join(', ') || '(none)';
                console.warn(`[bigraphide] Facade TIMEOUT: ${method} (requestId=${requestId}). Open sessions: ${openFiles}. Tip: Focus the bigraph diagram tab and try again.`);
                reject(new Error(
                    `Timeout waiting for response to ${method}. ` +
                    'Click on the bigraph diagram tab to focus it, then try again. The diagram must be the active tab for the request to be processed.'
                ));
            }, 10000);

            this.pendingRequests.set(requestId, {
                resolve: (value) => { clearTimeout(timeout); resolve(value); },
                reject: (reason) => { clearTimeout(timeout); reject(reason); },
            });

            // Reveal the diagram tab so the webview is not throttled (background tabs are often suspended in Electron/Cursor).
            session.panel.reveal();
            session.panel.webview.postMessage({
                type: 'bigraph-bridge-request',
                requestId,
                method,
                params,
            });
        });
    }

    getContext(): unknown {
        const active = this.registry.getActive();
        return {
            activeFile: active?.fileName ?? null,
            activeFilePath: active?.filePath ?? null,
            allOpenFiles: this.registry.getAllSessions().map(s => ({
                clientId: s.clientId,
                fileName: s.fileName,
                filePath: s.filePath,
            })),
        };
    }

    async getSummary(filePath?: string): Promise<unknown> {
        const session = this.resolveSession(filePath);
        const summary = await this.query('getSummary', {}, filePath);
        return { file: session.fileName, ...(summary as object) };
    }

    async getRoots(filePath?: string): Promise<unknown> {
        return this.query('getRoots', {}, filePath);
    }

    async getChildren(nodeId: string, filePath?: string): Promise<unknown> {
        return this.query('getChildren', { nodeId }, filePath);
    }

    async getNodeInfo(nodeId: string, filePath?: string): Promise<unknown> {
        return this.query('getNodeInfo', { nodeId }, filePath);
    }

    async findByControl(control: string, filePath?: string): Promise<unknown> {
        return this.query('findByControl', { control }, filePath);
    }

    async getSignature(filePath?: string): Promise<unknown> {
        return this.query('getSignature', {}, filePath);
    }

    async getLinks(filePath?: string): Promise<unknown> {
        return this.query('getLinks', {}, filePath);
    }

    async getNeighbors(nodeId: string, filePath?: string): Promise<unknown> {
        return this.query('getNeighbors', { nodeId }, filePath);
    }

    async addNode(parentId: string, controlName: string, filePath?: string): Promise<unknown> {
        return this.query('addNode', { parentId, controlName }, filePath);
    }

    async addSite(filePath?: string): Promise<unknown> {
        return this.query('addSite', {}, filePath);
    }

    async addInnerName(filePath?: string): Promise<unknown> {
        return this.query('addInnerName', {}, filePath);
    }

    async addOuterName(filePath?: string): Promise<unknown> {
        return this.query('addOuterName', {}, filePath);
    }

    async addEdge(filePath?: string): Promise<unknown> {
        return this.query('addEdge', {}, filePath);
    }

    async deleteElement(elementId: string, filePath?: string): Promise<unknown> {
        return this.query('deleteElement', { elementId }, filePath);
    }

    async createEdge(sourceId: string, targetId: string, filePath?: string): Promise<unknown> {
        return this.query('createEdge', { sourceId, targetId }, filePath);
    }

    async autoLayout(algorithm?: string, filePath?: string): Promise<unknown> {
        return this.query('autoLayout', { algorithm }, filePath);
    }
}
