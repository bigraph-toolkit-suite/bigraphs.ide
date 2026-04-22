/**
 * BigraphBridge: thin relay between Extension Host (postMessage) and GLSP Server (ActionDispatcher).
 *
 * READS:  Extension → postMessage → BigraphBridge → actionDispatcher.request(RequestAction) → GLSP Server → ResponseAction
 * WRITES: Extension → postMessage → BigraphBridge → actionDispatcher.dispatch(Operation|Action) → GLSP Server
 *
 * No GModel access, no bigraph logic in the webview. All bigraph intelligence lives on the server.
 */

import {
    IActionDispatcher,
    CreateNodeOperation,
    CreateEdgeOperation,
    DeleteElementOperation,
    TYPES
} from '@eclipse-glsp/client';
import { Container } from 'inversify';

interface BridgeRequest {
    type: 'bigraph-bridge-request';
    requestId: string;
    method: string;
    params: Record<string, unknown>;
}

interface BridgeResponse {
    type: 'bigraph-bridge-response';
    requestId: string;
    result?: unknown;
    error?: string;
}

export interface VsCodeApi {
    postMessage(msg: unknown): void;
}

export class BigraphBridge {
    private vscodeApi: VsCodeApi;
    private dispatcher: IActionDispatcher;

    constructor(container: Container, vscodeApi: VsCodeApi) {
        this.dispatcher = container.get<IActionDispatcher>(TYPES.IActionDispatcher);
        this.vscodeApi = vscodeApi;
        this.listen();
    }

    private listen(): void {
        window.addEventListener('message', (event: MessageEvent) => {
            const msg = event.data;
            console.log('[BigraphBridge] Raw message received:', msg?.type);
            if (msg && (msg as BridgeRequest).type === 'bigraph-bridge-request') {
                this.handleRequest(msg as BridgeRequest);
            }
        });
    }

    private async handleRequest(req: BridgeRequest): Promise<void> {
        console.log('[BigraphBridge] Received request:', req.method, req.requestId);
        try {
            const result = await this.route(req.method, req.params);
            console.log('[bigraphide-webview] Bridge response OK for', req.requestId);
            this.respond({ type: 'bigraph-bridge-response', requestId: req.requestId, result });
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            console.error('[bigraphide-webview] Bridge error for', req.method, req.requestId, message);
            this.respond({ type: 'bigraph-bridge-response', requestId: req.requestId, error: message });
        }
    }

    private respond(res: BridgeResponse): void {
        this.vscodeApi.postMessage(res);
    }

    private async route(method: string, params: Record<string, unknown>): Promise<unknown> {
        switch (method) {
            case 'getSummary':
                return this.serverRequest('bigraph.requestSummary', {});
            case 'getRoots':
                return this.serverRequest('bigraph.requestRoots', {});
            case 'getChildren':
                return this.serverRequest('bigraph.requestChildren', { nodeId: params.nodeId });
            case 'getNodeInfo':
                return this.serverRequest('bigraph.requestNodeInfo', { nodeId: params.nodeId });
            case 'findByControl':
                return this.serverRequest('bigraph.requestFindByControl', { control: params.control });
            case 'getSignature':
                return this.serverRequest('bigraph.requestSignature', {});
            case 'addControl':
                return this.dispatchAddControl(
                    params.name as string,
                    params.arity as number,
                    (params.status as string | undefined) ?? 'ATOMIC'
                );
            case 'getLinks':
                return this.serverRequest('bigraph.requestLinks', {});
            case 'getNeighbors':
                return this.serverRequest('bigraph.requestNeighbors', { nodeId: params.nodeId });

            case 'addNode':
                return this.dispatchAddNode(params.parentId as string, params.controlName as string);
            case 'addSite':
                return this.dispatchAddSite();
            case 'addInnerName':
                return this.dispatchAddInnerName();
            case 'addOuterName':
                return this.dispatchAddOuterName();
            case 'addEdge':
                return this.dispatchAddEdge();
            case 'deleteElement':
                return this.dispatchDeleteElement(params.elementId as string);
            case 'createEdge':
                return this.dispatchCreateEdge(params.sourceId as string, params.targetId as string);
            case 'autoLayout':
                return this.dispatchAutoLayout((params.algorithm as string) ?? 'layered');

            default:
                throw new Error(`Unknown method: ${method}`);
        }
    }

    /** Max time to wait for GLSP server response (must be below facade query timeout). */
    private static readonly GLSP_REQUEST_TIMEOUT_MS = 8000;

    /**
     * Send a RequestAction to the GLSP server and return the ResponseAction.
     * Server must have a corresponding ActionHandler that responds with matching requestId.
     */
    private async serverRequest(kind: string, params: Record<string, unknown>): Promise<unknown> {
        const requestId = this.generateId();
        console.log('[bigraphide-webview] GLSP request:', kind, 'params=', JSON.stringify(params), 'bridgeRequestId=', requestId);
        const requestPromise = this.dispatcher.request({
            kind,
            requestId,
            ...params
        } as Parameters<IActionDispatcher['request']>[0]);
        const timeoutPromise = new Promise<never>((_, reject) => {
            setTimeout(() => reject(new Error(
                `GLSP server did not respond to ${kind} within ${BigraphBridge.GLSP_REQUEST_TIMEOUT_MS / 1000}s. ` +
                'Is the diagram tab active and the GLSP server running?'
            )), BigraphBridge.GLSP_REQUEST_TIMEOUT_MS);
        });
        const result = await Promise.race([requestPromise, timeoutPromise]);
        console.log('[bigraphide-webview] GLSP response OK for', kind);
        return result;
    }

    private static readonly DEFAULT_LOCATION = { x: 0, y: 0 };

    private async dispatchAddNode(parentId: string, controlName: string): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateNodeOperation.create('bigraph:node', {
                location: BigraphBridge.DEFAULT_LOCATION,
                containerId: parentId,
                args: { controlName }
            })
        );
        return { success: true };
    }

    /**
     * Adds a control to the dynamic signature (companion .signature.xmi / .signature.ecore) and reloads the model.
     */
    private async dispatchAddControl(name: string, arity: number, status: string): Promise<unknown> {
        const trimmed = (name ?? '').trim();
        if (!trimmed) {
            throw new Error('addControl: name must be a non-empty string');
        }
        if (!Number.isInteger(arity) || arity < 0) {
            throw new Error('addControl: arity must be a non-negative integer');
        }
        const st = (status ?? 'ATOMIC').toUpperCase();
        await this.dispatcher.dispatch({
            kind: 'bigraph.createControl',
            name: trimmed,
            arity,
            status: st,
        } as { kind: string; name: string; arity: number; status: string });
        return { success: true, name: trimmed, arity, status: st };
    }

    private async dispatchAddSite(): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateNodeOperation.create('bigraph:site', {
                location: BigraphBridge.DEFAULT_LOCATION
            })
        );
        return { success: true };
    }

    private async dispatchAddInnerName(): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateNodeOperation.create('bigraph:inner-name', {
                location: BigraphBridge.DEFAULT_LOCATION
            })
        );
        return { success: true };
    }

    private async dispatchAddOuterName(): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateNodeOperation.create('bigraph:outer-name', {
                location: BigraphBridge.DEFAULT_LOCATION
            })
        );
        return { success: true };
    }

    private async dispatchAddEdge(): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateNodeOperation.create('bigraph:edge', {
                location: BigraphBridge.DEFAULT_LOCATION
            })
        );
        return { success: true };
    }

    private async dispatchDeleteElement(elementId: string): Promise<unknown> {
        await this.dispatcher.dispatch(DeleteElementOperation.create([elementId]));
        return { success: true };
    }

    private async dispatchCreateEdge(sourceId: string, targetId: string): Promise<unknown> {
        await this.dispatcher.dispatch(
            CreateEdgeOperation.create({
                elementTypeId: 'bigraph:connection',
                sourceElementId: sourceId,
                targetElementId: targetId
            })
        );
        return { success: true };
    }

    private async dispatchAutoLayout(algorithm: string): Promise<unknown> {
        await this.dispatcher.dispatch({
            kind: 'bigraph.autoLayout',
            algorithm,
            bigraphStandard: true
        } as { kind: string; algorithm: string; bigraphStandard: boolean });
        return { success: true };
    }

    private generateId(): string {
        return `bridge-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }
}
