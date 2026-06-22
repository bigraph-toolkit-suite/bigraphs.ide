import * as path from 'path';
import { isWorkspaceBigraphPath } from './bigraphArtifacts.js';

export interface WorkspaceBigraphSession {
	clientId: string;
	fsPath: string;
	evolutionFolder: string | null;
	/** Opened via Evolution Manager / tree / managed open helper. */
	managed: boolean;
	alignmentDirty: boolean;
	structureDirty: boolean;
	evolutionRunning: boolean;
}

const sessions = new Map<string, WorkspaceBigraphSession>();
const pendingManagedOpens = new Map<string, { evolutionFolder: string }>();

export function markPendingManagedOpen(fsPath: string, evolutionFolder: string): void {
	pendingManagedOpens.set(path.normalize(fsPath), { evolutionFolder });
}

export function consumePendingManagedOpen(fsPath: string): { evolutionFolder: string } | undefined {
	const key = path.normalize(fsPath);
	const entry = pendingManagedOpens.get(key);
	pendingManagedOpens.delete(key);
	return entry;
}

export function registerSession(
	clientId: string,
	fsPath: string,
	opts: { managed: boolean; evolutionFolder?: string | null }
): WorkspaceBigraphSession {
	const session: WorkspaceBigraphSession = {
		clientId,
		fsPath: path.normalize(fsPath),
		evolutionFolder: opts.evolutionFolder ?? null,
		managed: opts.managed,
		alignmentDirty: false,
		structureDirty: false,
		evolutionRunning: false,
	};
	sessions.set(clientId, session);
	return session;
}

export function unregisterSession(clientId: string): void {
	sessions.delete(clientId);
}

export function getSession(clientId: string): WorkspaceBigraphSession | undefined {
	return sessions.get(clientId);
}

export function getSessionForFsPath(fsPath: string): WorkspaceBigraphSession | undefined {
	const norm = path.normalize(fsPath);
	for (const session of sessions.values()) {
		if (session.fsPath === norm) { return session; }
	}
	return undefined;
}

export function isManagedWorkspaceBigraph(fsPath: string): boolean {
	const session = getSessionForFsPath(fsPath);
	return !!session?.managed && isWorkspaceBigraphPath(fsPath);
}

export function setSessionDirty(
	clientId: string,
	patch: { alignmentDirty?: boolean; structureDirty?: boolean }
): WorkspaceBigraphSession | undefined {
	const session = sessions.get(clientId);
	if (!session) { return undefined; }
	if (typeof patch.alignmentDirty === 'boolean') {
		session.alignmentDirty = patch.alignmentDirty;
	}
	if (typeof patch.structureDirty === 'boolean') {
		session.structureDirty = patch.structureDirty;
	}
	return session;
}

export function clearSessionDirty(clientId: string): void {
	const session = sessions.get(clientId);
	if (!session) { return; }
	session.alignmentDirty = false;
	session.structureDirty = false;
}

export function setEvolutionRunningForFolder(evolutionFolder: string | null, running: boolean): void {
	if (!evolutionFolder) { return; }
	const norm = path.normalize(evolutionFolder);
	for (const session of sessions.values()) {
		if (session.evolutionFolder === norm) {
			session.evolutionRunning = running;
		}
	}
}

export function updateSessionEvolutionFolder(clientId: string, evolutionFolder: string | null): void {
	const session = sessions.get(clientId);
	if (session) {
		session.evolutionFolder = evolutionFolder;
	}
}
