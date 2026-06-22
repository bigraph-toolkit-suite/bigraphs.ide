import * as path from 'path';
import {
	EVOLUTION_JSON,
	EvolutionCheckpoint,
	EvolutionSubdir,
	WORKSPACE_BIGRAPH_XMI,
} from './evolutionConstants.js';

export function evolutionJsonPath(folderPath: string): string {
	return path.join(folderPath, EVOLUTION_JSON);
}

export function originalCheckpointPath(evolutionFolder: string): string {
	return path.join(
		evolutionFolder,
		EvolutionSubdir.Checkpoints,
		`${EvolutionCheckpoint.OriginalBase}.xmi`
	);
}

export function workspaceBigraphPath(evolutionFolder: string): string {
	return path.join(evolutionFolder, WORKSPACE_BIGRAPH_XMI);
}
