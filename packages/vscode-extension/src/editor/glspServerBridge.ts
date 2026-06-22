import type { ReconnectingSocketGlspVscodeServer } from './glsp/reconnector';
import { getActiveClientInfo } from './glspClientRegistry.js';

let globalServer: ReconnectingSocketGlspVscodeServer | null = null;
let pendingRunFolder: string | null = null;

const readySessions = new Set<string>();
const readyListeners = new Map<string, Array<() => void>>();
const settlingClients = new Set<string>();
const settlingTimers = new Map<string, ReturnType<typeof setTimeout>>();

export function beginSessionSettling(clientId: string, onSettled: () => void, delayMs = 1500): void {
	settlingClients.add(clientId);
	const prevTimer = settlingTimers.get(clientId);
	if (prevTimer) { clearTimeout(prevTimer); }
	settlingTimers.set(clientId, setTimeout(() => {
		settlingClients.delete(clientId);
		settlingTimers.delete(clientId);
		onSettled();
	}, delayMs));
}

export function isSessionSettling(clientId: string): boolean {
	return settlingClients.has(clientId);
}

export function setGlspServer(server: ReconnectingSocketGlspVscodeServer | null): void {
	globalServer = server;
}

export function getGlspServer(): ReconnectingSocketGlspVscodeServer | null {
	return globalServer;
}

export function setPendingRunFolder(folder: string): void {
	pendingRunFolder = folder;
}

export function consumePendingRunFolder(): string | null {
	const folder = pendingRunFolder;
	pendingRunFolder = null;
	return folder;
}

export function markSessionReady(clientId: string): void {
	readySessions.add(clientId);
	const listeners = readyListeners.get(clientId);
	if (listeners) {
		readyListeners.delete(clientId);
		for (const cb of listeners) { cb(); }
	}
}

export function markSessionDisposed(clientId: string): void {
	readySessions.delete(clientId);
	readyListeners.delete(clientId);
	settlingClients.delete(clientId);
	const timer = settlingTimers.get(clientId);
	if (timer) { clearTimeout(timer); settlingTimers.delete(clientId); }
}

export function assumeSessionReady(clientId: string): void {
	readySessions.add(clientId);
}

export function isSessionReady(clientId: string): boolean {
	return readySessions.has(clientId);
}

export function waitForClientSession(clientId: string, timeoutMs = 8000): Promise<boolean> {
	if (readySessions.has(clientId)) { return Promise.resolve(true); }
	return new Promise<boolean>((resolve) => {
		const timer = setTimeout(() => {
			const arr = readyListeners.get(clientId);
			if (arr) {
				const idx = arr.indexOf(done);
				if (idx >= 0) { arr.splice(idx, 1); }
				if (arr.length === 0) { readyListeners.delete(clientId); }
			}
			resolve(false);
		}, timeoutMs);
		const done = (): void => { clearTimeout(timer); resolve(true); };
		let arr = readyListeners.get(clientId);
		if (!arr) { arr = []; readyListeners.set(clientId, arr); }
		arr.push(done);
	});
}

export function sendActionToServer(action: Record<string, unknown>, clientId?: string): boolean {
	const resolvedClientId = clientId ?? getActiveClientInfo()?.clientId;
	if (!resolvedClientId) {
		console.warn('[BigraphIDE] sendActionToServer: no active client found');
		return false;
	}
	if (!globalServer) {
		console.warn('[BigraphIDE] sendActionToServer: server not yet initialised');
		return false;
	}
	const message = { clientId: resolvedClientId, action };
	(globalServer as unknown as { onSendToServerEmitter: { fire(msg: unknown): void } })
		.onSendToServerEmitter.fire(message);
	return true;
}
