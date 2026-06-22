import * as fs from 'fs';
import * as path from 'path';
import type { EvolutionOperation } from '../evolutionManager/evolutionManagerState.js';
import { metaPathFromXmi } from './bigraphArtifacts.js';

/**
 * Walks from `cursorOperationId` toward the root and returns the first existing
 * `.bigraph-meta` path for a checkpoint on that chain.
 */
export function resolveInheritedMetaPath(
	operations: EvolutionOperation[],
	cursorOperationId: string | null,
	checkpointXmiAbs: string | null
): string | null {
	const byId = new Map<string, EvolutionOperation>();
	for (const op of operations) {
		if (op.id) { byId.set(op.id, op); }
	}

	const visited = new Set<string>();
	let currentId: string | null = cursorOperationId;

	while (currentId && !visited.has(currentId)) {
		visited.add(currentId);
		const op = byId.get(currentId);
		if (!op) { break; }

		if (op.result) {
			const meta = metaPathFromXmi(op.result);
			if (fs.existsSync(meta)) { return meta; }
		}

		const pred = op.predecessor && op.predecessor !== 'null' ? op.predecessor : null;
		currentId = pred;
	}

	if (checkpointXmiAbs) {
		const direct = metaPathFromXmi(checkpointXmiAbs);
		if (fs.existsSync(direct)) { return direct; }
	}

	return null;
}

export function applyMetaToWorkspace(
	metaSourcePath: string | null,
	workspaceXmiAbs: string
): void {
	const wsMeta = metaPathFromXmi(workspaceXmiAbs);
	if (metaSourcePath && fs.existsSync(metaSourcePath)) {
		fs.copyFileSync(metaSourcePath, wsMeta);
		return;
	}
	try {
		if (fs.existsSync(wsMeta)) { fs.unlinkSync(wsMeta); }
	} catch { /* ignore */ }
}

export function copyWorkspaceMetaToCheckpoint(
	workspaceXmiAbs: string,
	checkpointXmiAbs: string
): void {
	const wsMeta = metaPathFromXmi(workspaceXmiAbs);
	if (!fs.existsSync(wsMeta)) { return; }
	const cpMeta = metaPathFromXmi(checkpointXmiAbs);
	fs.copyFileSync(wsMeta, cpMeta);
}
