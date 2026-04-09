export type DndPayloadType = 'bigraphFiles' | 'rewriteRulePayload';

export interface DndZoneDescriptor {
    zoneId: string;
    accepts: DndPayloadType[];
    priority?: number;
}

export interface DndSessionStartedEvent {
    type: 'sessionStarted';
    sessionId: string;
    payloadType: DndPayloadType;
}

export interface DndSessionEndedEvent {
    type: 'sessionEnded';
    sessionId: string;
    reason: string;
}

export type DndServiceEvent = DndSessionStartedEvent | DndSessionEndedEvent;

export interface DndDropResult<TPayload = unknown> {
    sessionId: string;
    webviewId: string;
    zoneId: string;
    payloadType: DndPayloadType;
    payload: TPayload;
}

function logDnd(_scope: string, _event: string, _details?: unknown): void {
    // DnD debug channel removed intentionally.
}

interface DragSession {
    sessionId: string;
    payloadType: DndPayloadType;
    payload: unknown;
    startedAt: number;
    sourceEnded: boolean;
}

interface ZoneState {
    descriptor: DndZoneDescriptor;
    hovered: boolean;
}

class DragService {
    private session: DragSession | null = null;
    private sessionCounter = 0;
    private readonly listeners = new Set<(event: DndServiceEvent) => void>();
    private readonly zonesByWebview = new Map<string, Map<string, ZoneState>>();
    private sourceEndTimer: ReturnType<typeof setTimeout> | null = null;
    private static readonly SOURCE_END_GRACE_MS = 10_000;

    startSession(payloadType: DndPayloadType, payload: unknown): string {
        this.clearSourceEndTimer();
        if (this.session) {
            this.endSessionInternal('newSession');
        }
        const sessionId = `dnd-${Date.now()}-${++this.sessionCounter}`;
        this.session = {
            sessionId,
            payloadType,
            payload,
            startedAt: Date.now(),
            sourceEnded: false
        };
        this.resetAllHoverStates();
        logDnd('dnd.service', 'startSession', { sessionId, payloadType });
        this.emit({ type: 'sessionStarted', sessionId, payloadType });
        return sessionId;
    }

    markSourceEnded(): void {
        if (!this.session) {
            return;
        }
        this.session.sourceEnded = true;
        const sessionId = this.session.sessionId;
        logDnd('dnd.service', 'markSourceEnded', { sessionId });
        this.clearSourceEndTimer();
        this.sourceEndTimer = setTimeout(() => {
            if (this.session?.sessionId !== sessionId) {
                return;
            }
            this.endSessionInternal('sourceEndTimeout');
        }, DragService.SOURCE_END_GRACE_MS);
    }

    registerZone(webviewId: string, descriptor: DndZoneDescriptor): void {
        if (!this.zonesByWebview.has(webviewId)) {
            this.zonesByWebview.set(webviewId, new Map());
        }
        this.zonesByWebview.get(webviewId)!.set(descriptor.zoneId, {
            descriptor,
            hovered: false
        });
        logDnd('dnd.service', 'registerZone', { webviewId, zoneId: descriptor.zoneId, accepts: descriptor.accepts });
    }

    unregisterZone(webviewId: string, zoneId: string): void {
        this.zonesByWebview.get(webviewId)?.delete(zoneId);
        logDnd('dnd.service', 'unregisterZone', { webviewId, zoneId });
    }

    setHover(webviewId: string, zoneId: string | null): void {
        const zones = this.zonesByWebview.get(webviewId);
        if (!zones) {
            return;
        }
        for (const state of zones.values()) {
            state.hovered = false;
        }
        if (zoneId && zones.has(zoneId)) {
            zones.get(zoneId)!.hovered = true;
        }
    }

    finalize(webviewId: string, reason: string): DndDropResult | null {
        if (!this.session) {
            logDnd('dnd.service', 'finalize-no-session', { webviewId, reason });
            return null;
        }
        const zones = this.zonesByWebview.get(webviewId);
        if (!zones || zones.size === 0) {
            logDnd('dnd.service', 'finalize-no-zones', { webviewId, reason });
            return null;
        }

        const hovered = [...zones.values()]
            .filter((z) => z.hovered)
            .sort((a, b) => (b.descriptor.priority ?? 0) - (a.descriptor.priority ?? 0));
        if (hovered.length === 0) {
            logDnd('dnd.service', 'finalize-no-hovered-zone', { webviewId, reason });
            return null;
        }

        const zone = hovered[0].descriptor;
        if (!zone.accepts.includes(this.session.payloadType)) {
            logDnd('dnd.service', 'finalize-type-mismatch', {
                webviewId,
                zoneId: zone.zoneId,
                payloadType: this.session.payloadType,
                accepts: zone.accepts
            });
            return null;
        }

        const result: DndDropResult = {
            sessionId: this.session.sessionId,
            webviewId,
            zoneId: zone.zoneId,
            payloadType: this.session.payloadType,
            payload: this.session.payload
        };
        logDnd('dnd.service', 'finalize-success', {
            sessionId: result.sessionId,
            webviewId: result.webviewId,
            zoneId: result.zoneId,
            payloadType: result.payloadType,
            reason
        });
        this.endSessionInternal('dropDelivered');
        return result;
    }

    subscribe(listener: (event: DndServiceEvent) => void): () => void {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    }

    private endSessionInternal(reason: string): void {
        if (!this.session) {
            return;
        }
        const ended = this.session;
        this.session = null;
        this.clearSourceEndTimer();
        this.resetAllHoverStates();
        logDnd('dnd.service', 'endSession', { sessionId: ended.sessionId, reason });
        this.emit({ type: 'sessionEnded', sessionId: ended.sessionId, reason });
    }

    private resetAllHoverStates(): void {
        for (const zones of this.zonesByWebview.values()) {
            for (const state of zones.values()) {
                state.hovered = false;
            }
        }
    }

    private clearSourceEndTimer(): void {
        if (this.sourceEndTimer) {
            clearTimeout(this.sourceEndTimer);
            this.sourceEndTimer = null;
        }
    }

    private emit(event: DndServiceEvent): void {
        for (const listener of this.listeners) {
            try {
                listener(event);
            } catch (error) {
                logDnd('dnd.service', 'listenerError', {
                    eventType: event.type,
                    error: error instanceof Error ? error.message : String(error)
                });
            }
        }
    }
}

let singleton: DragService | null = null;

export function getDragService(): DragService {
    if (!singleton) {
        singleton = new DragService();
    }
    return singleton;
}
