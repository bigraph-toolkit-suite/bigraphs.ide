import type { VsCodeApi } from '../types.js';
import type { DndPayloadType } from '../../../dragging';

type DropCallback = (payload: unknown) => void;

interface DropZoneConfig {
    zoneId: string;
    element: HTMLElement;
    accepts: DndPayloadType[];
    priority?: number;
    onDrop: DropCallback;
}

interface SessionStartedMsg {
    type: 'dndSessionStarted';
    sessionId: string;
    payloadType: DndPayloadType;
}

interface SessionEndedMsg {
    type: 'dndSessionEnded';
    sessionId: string;
    reason: string;
}

interface DropDeliveredMsg {
    type: 'dndDropDelivered';
    sessionId: string;
    zoneId: string;
    payloadType: DndPayloadType;
    payload: unknown;
}

type InboundMsg = SessionStartedMsg | SessionEndedMsg | DropDeliveredMsg;

export class WebviewDndBridge {
    private readonly vscode: VsCodeApi;
    private readonly zones = new Map<string, DropZoneConfig>();
    private activeSessionId: string | null = null;
    private hoveredZoneId: string | null = null;
    private lastPointer: { x: number; y: number } | null = null;

    constructor(vscode: VsCodeApi) {
        this.vscode = vscode;
    }

    registerDropZonePayload(
        element: HTMLElement,
        payloadTypeToReceive: DndPayloadType,
        callback: DropCallback,
        zoneId: string,
        priority = 0
    ): void {
        const config: DropZoneConfig = {
            zoneId,
            element,
            accepts: [payloadTypeToReceive],
            priority,
            onDrop: callback
        };
        this.zones.set(config.zoneId, config);
        this.vscode.postMessage({
            type: 'dndRegisterZone',
            zoneId: config.zoneId,
            accepts: config.accepts,
            priority: config.priority ?? 0
        });
    }

    registerDragZone(_element: HTMLElement, _payloadObject: unknown, _typeOfPayload: DndPayloadType): void {
        // Drag sources in this extension are VS Code tree views (extension-host side).
        // Keep this API so webview components stay declarative and symmetric.
    }

    init(): void {
        window.addEventListener('message', (event) => this.handleMessage(event.data as InboundMsg));

        document.addEventListener('dragenter', (e) => {
            e.preventDefault();
            this.lastPointer = { x: e.clientX, y: e.clientY };
            this.updateHoverFromPoint(e.clientX, e.clientY);
            this.updateHoverFromEventTarget(e.target as Node | null);
        }, true);
        document.addEventListener('dragover', (e) => {
            e.preventDefault();
            this.lastPointer = { x: e.clientX, y: e.clientY };
            this.updateHoverFromPoint(e.clientX, e.clientY);
            this.updateHoverFromEventTarget(e.target as Node | null);
        }, true);
        document.addEventListener('dragleave', (e) => {
            const rel = e.relatedTarget as Node | null;
            if (rel) {
                this.updateHoverFromEventTarget(rel);
            }
            // With VS Code webviews, relatedTarget can be null during internal transitions.
            // Do not clear hover eagerly; next dragover/drop recomputes from pointer.
        }, true);
        document.addEventListener('drop', (e) => {
            e.preventDefault();
            e.stopPropagation();
            this.lastPointer = { x: e.clientX, y: e.clientY };
            this.updateHoverFromPoint(e.clientX, e.clientY);
            this.updateHoverFromEventTarget(e.target as Node | null);
            this.tryFinalize('drop');
        }, true);
        document.addEventListener('mouseup', () => this.tryFinalize('mouseup'), true);
    }

    private handleMessage(msg: InboundMsg): void {
        if (!msg || typeof msg !== 'object' || !('type' in msg)) {
            return;
        }
        if (msg.type === 'dndSessionStarted') {
            this.activeSessionId = msg.sessionId;
            this.updateHover(null);
            return;
        }
        if (msg.type === 'dndSessionEnded') {
            if (this.activeSessionId && this.activeSessionId === msg.sessionId) {
                this.activeSessionId = null;
                this.updateHover(null);
            }
            return;
        }
        if (msg.type === 'dndDropDelivered') {
            const zone = this.zones.get(msg.zoneId);
            if (!zone) {
                return;
            }
            zone.onDrop(msg.payload);
        }
    }

    private updateHoverFromEventTarget(target: Node | null): void {
        if (!target) {
            return;
        }
        for (const zone of this.zones.values()) {
            if (zone.element.contains(target)) {
                this.updateHover(zone.zoneId);
                return;
            }
        }
    }

    private updateHoverFromPoint(clientX: number, clientY: number): void {
        const node = document.elementFromPoint(clientX, clientY);
        if (!node) {
            return;
        }
        for (const zone of this.zones.values()) {
            if (zone.element.contains(node)) {
                this.updateHover(zone.zoneId);
                return;
            }
        }
    }

    private updateHover(zoneId: string | null): void {
        if (this.hoveredZoneId === zoneId) {
            return;
        }
        this.hoveredZoneId = zoneId;
        this.vscode.postMessage({ type: 'dndHoverZone', zoneId });
    }

    private tryFinalize(reason: string): void {
        if (!this.hoveredZoneId && this.lastPointer) {
            this.updateHoverFromPoint(this.lastPointer.x, this.lastPointer.y);
        }
        if (!this.hoveredZoneId) {
            return;
        }
        this.vscode.postMessage({ type: 'dndFinalize', reason });
    }
}
