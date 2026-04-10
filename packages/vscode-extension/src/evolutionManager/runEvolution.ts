import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import EditorProvider from '../editor/editorProvider';

export interface EvolutionActionPayload {
	actionType: string;
	clientId: string | null;
	/** For pause: the id of the running operation to cancel. */
	operationId?: string | null;
	evolutionLabel: string;
	/** Absolute fsPath to the bigraph the user selected (used locally for folder setup). */
	bigraphPath: string;
	rewriteRules: { id?: string; label?: string; redexPath: string; reactumPath: string; active?: boolean }[];
	verificationBigraphs?: { id?: string; label?: string; path: string; stop?: boolean }[];
	maxOperationsEnabled: boolean;
	maxOperations: number;
	checkpointFileGeneration: boolean;
	visualizeIntermediateSteps: boolean;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns a unique folder path of the form <base>.evolution, <base>1.evolution, <base>2.evolution, … */
function uniqueEvolutionFolderPath(workspaceRoot: string, labelSlug: string): string {
	const base = path.join(workspaceRoot, `${labelSlug}.evolution`);
	if (!fs.existsSync(base)) { return base; }
	let n = 1;
	let candidate: string;
	do {
		candidate = path.join(workspaceRoot, `${labelSlug}${n}.evolution`);
		n++;
	} while (fs.existsSync(candidate));
	return candidate;
}

/**
 * Copies a bigraph triplet (.xmi + .signature.ecore + .signature.xmi) into destDir.
 * If a file with the same base name already exists, a counter suffix is appended.
 * Returns the base name (without extension) that was used for the copy.
 */
function copyBigraphTriplet(xmiPath: string, destDir: string): string {
	const ext = path.extname(xmiPath);                     // ".xmi"
	const base = path.basename(xmiPath, ext);              // e.g. "left"
	const srcDir = path.dirname(xmiPath);

	// Find a unique base name in destDir (shared by all 3 files)
	let destBase = base;
	let counter = 1;
	while (
		fs.existsSync(path.join(destDir, `${destBase}${ext}`)) ||
		fs.existsSync(path.join(destDir, `${destBase}.signature.ecore`)) ||
		fs.existsSync(path.join(destDir, `${destBase}.signature.xmi`))
	) {
		destBase = `${base}${counter}`;
		counter++;
	}

	// Copy all three files
	fs.copyFileSync(xmiPath, path.join(destDir, `${destBase}${ext}`));

	const ecoreSrc = path.join(srcDir, `${base}.signature.ecore`);
	const xmiSigSrc = path.join(srcDir, `${base}.signature.xmi`);
	if (fs.existsSync(ecoreSrc)) {
		fs.copyFileSync(ecoreSrc, path.join(destDir, `${destBase}.signature.ecore`));
	}
	if (fs.existsSync(xmiSigSrc)) {
		fs.copyFileSync(xmiSigSrc, path.join(destDir, `${destBase}.signature.xmi`));
	}

	return destBase;
}

/**
 * Returns true if any of the 3 bigraph files (base + .xmi/.signature.ecore/.signature.xmi)
 * differ between srcBase and dstBase paths.
 */
export function bigraphTripletsDiffer(srcBase: string, dstBase: string): boolean {
	for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
		const src = srcBase + ext;
		const dst = dstBase + ext;
		if (!fs.existsSync(src)) { continue; }
		const srcBuf = fs.readFileSync(src);
		const dstBuf = fs.existsSync(dst) ? fs.readFileSync(dst) : null;
		if (!dstBuf || !srcBuf.equals(dstBuf)) { return true; }
	}
	return false;
}

/** Copies the 3 bigraph files from srcBase to dstBase, only if they differ.
 *  Returns true if at least one file was actually copied. */
export function copyBigraphTripletIfDifferent(srcBase: string, dstBase: string): boolean {
	let anyCopied = false;
	for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
		const src = srcBase + ext;
		const dst = dstBase + ext;
		if (!fs.existsSync(src)) { continue; }
		const srcBuf = fs.readFileSync(src);
		const dstBuf = fs.existsSync(dst) ? fs.readFileSync(dst) : null;
		if (!dstBuf || !srcBuf.equals(dstBuf)) {
			fs.copyFileSync(src, dst);
			console.log(`[Evolution] Restored ${path.basename(dst)} from checkpoint.`);
			anyCopied = true;
		}
	}
	return anyCopied;
}

function randomId(length: number): string {
	const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
	let result = '';
	for (let i = 0; i < length; i++) {
		result += chars[Math.floor(Math.random() * chars.length)];
	}
	return result;
}

// ---------------------------------------------------------------------------
// Shared helper
// ---------------------------------------------------------------------------

/**
 * Ensures the given bigraph file is open and focused as the active GLSP diagram tab,
 * then resolves the GLSP clientId for that file.
 * Returns the clientId, or undefined if the file could not be registered.
 */
async function focusBigraphAndResolveClientId(fsPath: string, forceReload = false): Promise<string | undefined> {
	const existingClientId = EditorProvider.getClientIdForFsPath(fsPath);

	if (forceReload) {
		const { markSessionDisposed } = await import('../editor/glsp/connector.js');
		if (existingClientId) { markSessionDisposed(existingClientId); }

		for (const group of vscode.window.tabGroups.all) {
			for (const tab of group.tabs) {
				if (tab.input instanceof vscode.TabInputCustom && tab.input.uri.fsPath === fsPath) {
					await vscode.window.tabGroups.close(tab);
				}
			}
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
	} else if (existingClientId) {
		const { assumeSessionReady } = await import('../editor/glsp/connector.js');
		assumeSessionReady(existingClientId);
	}

	await vscode.commands.executeCommand('vscode.openWith', vscode.Uri.file(fsPath), 'bigraph.glspDiagram');
	await new Promise((resolve) => setTimeout(resolve, 300));

	const clientId = EditorProvider.getClientIdForFsPath(fsPath);
	if (!clientId) { return undefined; }

	const { waitForClientSession } = await import('../editor/glsp/connector.js');
	const ready = await waitForClientSession(clientId);
	if (!ready) {
		console.warn(`[BigraphIDE] GLSP session for ${clientId} did not become ready within timeout`);
	}
	return clientId;
}

// ---------------------------------------------------------------------------
// Existing evolution: just focus the workspace-bigraph and dispatch
// ---------------------------------------------------------------------------

/**
 * Used when play/step is clicked on an already-prepared evolution (config exists).
 * Validates the config, restores the workspace-bigraph from the cursor checkpoint if needed,
 * focuses the workspace-bigraph tab, resolves the GLSP client context, and dispatches.
 */
export async function dispatchEvolutionRunForExisting(payload: EvolutionActionPayload & {
	evolutionFolder: string;
	workspaceBigraphPath: string;
}): Promise<void> {
	const {
		actionType,
		rewriteRules,
		maxOperationsEnabled,
		maxOperations,
		checkpointFileGeneration,
		visualizeIntermediateSteps,
		evolutionFolder,
		workspaceBigraphPath
	} = payload;

	const activeRules = rewriteRules.filter((r) => r.active !== false);
	if (activeRules.length === 0) {
		vscode.window.showErrorMessage('At least one active rewrite rule is required.');
		return;
	}

	// --- Validate evolution.json ---
	const evoJsonPath = path.join(evolutionFolder, 'evolution.json');
	let evoConfig: Record<string, unknown>;
	try {
		evoConfig = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8'));
	} catch {
		// Config missing or corrupt – fall back to full folder preparation
		vscode.window.showWarningMessage('evolution.json not found or invalid – starting fresh evolution.');
		await prepareEvolutionRunFolder(payload);
		return;
	}

	// --- Restore workspace-bigraph from cursor checkpoint if available ---
	// The checkpoint-cursor points to the operation we are currently "at".
	// If that operation has a checkpoint file, overwrite workspace-bigraph with it
	// so the next run continues from exactly that state.
	let workspaceBigraphRestored = false;
	const cursorId = typeof evoConfig['checkpoint-cursor'] === 'string' ? evoConfig['checkpoint-cursor'] : null;
	if (cursorId) {
		const operations = Array.isArray(evoConfig['operations'])
			? (evoConfig['operations'] as Record<string, unknown>[])
			: [];
		const cursorOp = operations.find((op) => op['id'] === cursorId);
		const resultRelPath = cursorOp && typeof cursorOp['result'] === 'string' ? cursorOp['result'] : null;

		if (resultRelPath) {
			// Checkpoint file exists for the cursor position – restore it into workspace-bigraph
			const checkpointAbs = path.join(evolutionFolder, resultRelPath);
			if (fs.existsSync(checkpointAbs)) {
				const cpBase = checkpointAbs.replace(/\.xmi$/, '');
				const wsBase = workspaceBigraphPath.replace(/\.xmi$/, '');
				// Only prompt if at least one file actually differs
				if (bigraphTripletsDiffer(cpBase, wsBase)) {
					const answer = await vscode.window.showWarningMessage(
						'The workspace bigraph differs from the checkpoint the cursor is pointing to. Overwrite workspace-bigraph with the checkpoint?',
						{ modal: true },
						'Overwrite'
					);
					if (answer !== 'Overwrite') { return; }
					copyBigraphTripletIfDifferent(cpBase, wsBase);
					workspaceBigraphRestored = true;
				}
			} else {
				vscode.window.showWarningMessage(`Checkpoint file not found: ${resultRelPath}. Using existing workspace-bigraph.`);
			}
		}
		// If cursorOp has no result (e.g. it was a manual edit with no checkpoint file), keep workspace-bigraph as-is
	}

	// --- Ensure workspace-bigraph is open and focused before resolving clientId ---
	// Force-reload the tab when the file content was replaced so the GLSP server
	// picks up the restored bigraph state from disk instead of its in-memory model.
	const clientId = await focusBigraphAndResolveClientId(workspaceBigraphPath, workspaceBigraphRestored);

	const checkpointsDir = path.join(evolutionFolder, 'checkpoints');

	// Map activeRules to the PreparedRule shape expected by dispatchToGlsp,
	// resolving any relative paths to absolute ones under the evolutionFolder.
	const preparedRules: PreparedRule[] = activeRules.map((r) => ({
		id: r.id ?? '',
		label: r.label ?? '',
		redex: path.isAbsolute(r.redexPath)
			? path.relative(evolutionFolder, r.redexPath)
			: r.redexPath,
		reactum: path.isAbsolute(r.reactumPath)
			? path.relative(evolutionFolder, r.reactumPath)
			: r.reactumPath
	}));

	// Read verification bigraphs from evolution.json (these are already copied into the folder)
	const rawVerification = Array.isArray(evoConfig['verification'])
		? (evoConfig['verification'] as Record<string, unknown>[])
		: [];
	const preparedVerification: PreparedVerificationBigraph[] = rawVerification
		.filter((v) => v['stop'] !== false)
		.map((v) => ({
			id: String(v['id'] ?? ''),
			label: String(v['label'] ?? ''),
			path: String(v['path'] ?? ''),
			stop: true
		}));

	try {
		await dispatchToGlsp({
			actionType,
			rules: preparedRules,
			verificationBigraphs: preparedVerification,
			evolutionFolder,
			checkpointsDir,
			maxOperationsEnabled,
			maxOperations,
			checkpointFileGeneration,
			visualizeIntermediateSteps,
			clientId: clientId ?? undefined
		});
	} catch (e) {
		const msg = e instanceof Error ? e.message : String(e);
		vscode.window.showErrorMessage(`Failed to dispatch evolution action: ${msg}`);
	}
}

// ---------------------------------------------------------------------------
// New evolution: create folder structure, then dispatch
// ---------------------------------------------------------------------------

interface EvolutionFolderSetup {
	evolutionFolder: string;
	rulesDir: string;
	verificationDir: string;
	checkpointsDir: string;
	/** checkpoints/original.xmi – the immutable snapshot of the starting bigraph. */
	checkpointOriginalDest: string;
	workspaceBigraphDest: string;
}

interface PreparedRule {
	id: string;
	label: string;
	redex: string;   // relative path inside evolutionFolder, e.g. "rules/left.xmi"
	reactum: string; // relative path inside evolutionFolder
}

interface PreparedVerificationBigraph {
	id: string;
	label: string;
	path: string; // relative path inside evolutionFolder, e.g. "verification/my-bigraph.xmi"
	stop: boolean;
}

/** Validates payload inputs. Returns an error message string, or null if valid. */
function validatePayload(
	payload: EvolutionActionPayload,
	workspaceRoot: string
): { bigraphAbs: string; activeRules: NonNullable<EvolutionActionPayload['rewriteRules']> } | null {
	const { evolutionLabel, bigraphPath, rewriteRules } = payload;
	if (!evolutionLabel.trim()) {
		vscode.window.showErrorMessage('Evolution label is required.');
		return null;
	}
	if (!bigraphPath.trim()) {
		vscode.window.showErrorMessage('Bigraph path is required.');
		return null;
	}
	const bigraphAbs = path.isAbsolute(bigraphPath) ? bigraphPath : path.join(workspaceRoot, bigraphPath);
	if (!fs.existsSync(bigraphAbs)) {
		vscode.window.showErrorMessage(`Bigraph file not found: ${bigraphPath}`);
		return null;
	}
	const activeRules = rewriteRules.filter((r) => r.active !== false);
	if (activeRules.length === 0) {
		vscode.window.showErrorMessage('At least one active rewrite rule is required.');
		return null;
	}
	return { bigraphAbs, activeRules };
}

/** Creates the evolution folder structure and returns the relevant paths. */
function createEvolutionFolderStructure(workspaceRoot: string, evolutionLabel: string): EvolutionFolderSetup {
	const labelSlug = evolutionLabel.trim().toLowerCase().replace(/\s+/g, '-');
	const evolutionFolder = uniqueEvolutionFolderPath(workspaceRoot, labelSlug);
	const rulesDir = path.join(evolutionFolder, 'rules');
	const verificationDir = path.join(evolutionFolder, 'verification');
	const checkpointsDir = path.join(evolutionFolder, 'checkpoints');
	fs.mkdirSync(evolutionFolder, { recursive: true });
	fs.mkdirSync(rulesDir, { recursive: true });
	fs.mkdirSync(verificationDir, { recursive: true });
	fs.mkdirSync(checkpointsDir, { recursive: true });
	return {
		evolutionFolder,
		rulesDir,
		verificationDir,
		checkpointsDir,
		checkpointOriginalDest: path.join(checkpointsDir, 'original.xmi'),
		workspaceBigraphDest: path.join(evolutionFolder, 'workspace-bigraph.xmi')
	};
}

/**
 * Copies the source bigraph triplet into checkpoints/original.xmi (+ signature files).
 * This becomes the immutable starting-point entry in the operations list.
 */
function copyOriginalBigraphToCheckpoints(bigraphAbs: string, setup: EvolutionFolderSetup): void {
	const srcDir = path.dirname(bigraphAbs);
	const srcBase = path.basename(bigraphAbs, path.extname(bigraphAbs));
	const destBase = path.join(setup.checkpointsDir, 'original');

	fs.copyFileSync(bigraphAbs, setup.checkpointOriginalDest);
	for (const ext of ['.signature.ecore', '.signature.xmi']) {
		const src = path.join(srcDir, `${srcBase}${ext}`);
		if (fs.existsSync(src)) {
			fs.copyFileSync(src, `${destBase}${ext}`);
		}
	}
}

/**
 * Copies checkpoints/original.xmi (+ signature files) to workspace-bigraph.*
 * so the first run starts from the original state.
 */
function createWorkspaceBigraphCopy(setup: EvolutionFolderSetup): void {
	const destBase = path.join(setup.evolutionFolder, 'workspace-bigraph');
	const srcBase = path.join(setup.checkpointsDir, 'original');

	fs.copyFileSync(setup.checkpointOriginalDest, setup.workspaceBigraphDest);
	for (const ext of ['.signature.ecore', '.signature.xmi']) {
		const src = `${srcBase}${ext}`;
		if (fs.existsSync(src)) {
			fs.copyFileSync(src, `${destBase}${ext}`);
		}
	}
}

/**
 * Copies each active rule's redex and reactum triplets into rules/,
 * returns the list of rule entries for evolution.json.
 */
function copyRuleFiles(
	activeRules: EvolutionActionPayload['rewriteRules'],
	rulesDir: string,
	workspaceRoot: string
): PreparedRule[] {
	const evolutionRules: PreparedRule[] = [];
	for (const rule of activeRules) {
		const redexAbs = path.isAbsolute(rule.redexPath) ? rule.redexPath : path.join(workspaceRoot, rule.redexPath);
		const reactumAbs = path.isAbsolute(rule.reactumPath) ? rule.reactumPath : path.join(workspaceRoot, rule.reactumPath);
		if (!fs.existsSync(redexAbs) || !fs.existsSync(reactumAbs)) {
			vscode.window.showWarningMessage(`Rule files not found: ${rule.redexPath} / ${rule.reactumPath}, skipping.`);
			continue;
		}
		const redexBase = copyBigraphTriplet(redexAbs, rulesDir);
		const reactumBase = copyBigraphTriplet(reactumAbs, rulesDir);
		evolutionRules.push({
			id: randomId(10),
			label: rule.label ?? '',
			redex: `rules/${redexBase}.xmi`,
			reactum: `rules/${reactumBase}.xmi`
		});
	}
	return evolutionRules;
}

/**
 * Copies each verification bigraph triplet into verification/,
 * returns the list of verification entries for evolution.json.
 */
function copyVerificationFiles(
	verificationBigraphs: NonNullable<EvolutionActionPayload['verificationBigraphs']>,
	verificationDir: string,
	workspaceRoot: string
): PreparedVerificationBigraph[] {
	const result: PreparedVerificationBigraph[] = [];
	for (const vb of verificationBigraphs) {
		if (!vb.path) { continue; }
		const absPath = path.isAbsolute(vb.path) ? vb.path : path.join(workspaceRoot, vb.path);
		if (!fs.existsSync(absPath)) {
			vscode.window.showWarningMessage(`Verification bigraph not found: ${vb.path}, skipping.`);
			continue;
		}
		const destBase = copyBigraphTriplet(absPath, verificationDir);
		const label = vb.label ?? path.basename(absPath, path.extname(absPath));
		result.push({
			id: vb.id ?? randomId(10),
			label,
			path: `verification/${destBase}.xmi`,
			stop: vb.stop !== false
		});
	}
	return result;
}

/**
 * Writes the initial evolution.json.
 * The original bigraph is represented as the first operation entry (type "original")
 * whose result points to checkpoints/original.xmi. All subsequent rule operations
 * reference this entry as their chain root via the predecessor field.
 */
function writeEvolutionJson(evolutionFolder: string, evolutionLabel: string, rules: PreparedRule[], verification: PreparedVerificationBigraph[]): string {
	const originalOpId = randomId(10);
	const evolutionJson: Record<string, unknown> = {
		label: evolutionLabel,
		rules,
		verification,
		'workspace-bigraph': 'workspace-bigraph.xmi',
		operations: [
			{
				id: originalOpId,
				type: 'original',
				date: new Date().toISOString(),
				predecessor: null,
				result: 'checkpoints/original.xmi',
				rule: null
			}
		],
		'checkpoint-cursor': originalOpId
	};
	fs.writeFileSync(
		path.join(evolutionFolder, 'evolution.json'),
		JSON.stringify(evolutionJson, null, 2) + '\n',
		'utf8'
	);
	return originalOpId;
}

/** Notifies the Evolution Manager to update its bigraph input field. */
async function notifyEvolutionManagerBigraphChanged(workspaceBigraphDest: string): Promise<void> {
	const { getEvolutionManagerViewProvider } = await import('./evolutionManagerViewProvider.js');
	const evolutionManager = getEvolutionManagerViewProvider();
	if (evolutionManager) {
		evolutionManager.postMessage({
			type: 'activeTabChanged',
			clientId: null,
			fsPath: workspaceBigraphDest,
			relativePath: vscode.workspace.asRelativePath(workspaceBigraphDest)
		});
	}
}

/** Dispatches the EvolutionRunAction to the GLSP server. */
async function dispatchToGlsp(params: {
	actionType: string;
	rules: PreparedRule[];
	verificationBigraphs: PreparedVerificationBigraph[];
	evolutionFolder: string;
	checkpointsDir: string;
	maxOperationsEnabled: boolean;
	maxOperations: number;
	checkpointFileGeneration: boolean;
	visualizeIntermediateSteps: boolean;
	clientId: string | undefined;
}): Promise<void> {
	const { setPendingRunFolder, sendActionToServer } = await import('../editor/glsp/connector.js');
	setPendingRunFolder(params.evolutionFolder);

	// Only pass stop-tagged verification bigraphs with absolute paths to the backend
	const stopVerifications = params.verificationBigraphs
		.filter((v) => v.stop)
		.map((v) => ({
			id: v.id,
			path: path.isAbsolute(v.path) ? v.path : path.join(params.evolutionFolder, v.path)
		}));

	const sent = sendActionToServer({
		kind: 'bigraph.evolutionRun',
		actionType: params.actionType,
		rules: params.rules.map((r) => ({
			id: r.id,
			redex: path.join(params.evolutionFolder, r.redex),
			reactum: path.join(params.evolutionFolder, r.reactum)
		})),
		verificationBigraphs: stopVerifications,
		checkpointsDir: params.checkpointsDir,
		evolutionJsonPath: path.join(params.evolutionFolder, 'evolution.json'),
		maxOperationsEnabled: params.maxOperationsEnabled,
		maxOperations: params.maxOperations,
		checkpointFileGeneration: params.checkpointFileGeneration,
		visualizeIntermediateSteps: params.visualizeIntermediateSteps
	}, params.clientId);

	if (!sent) {
		vscode.window.showErrorMessage('GLSP server not available. Please open a bigraph diagram first.');
	}
}

/**
 * Prepares a new .evolution project folder, writes evolution.json,
 * then dispatches an EvolutionRunAction to the GLSP server.
 */
export async function prepareEvolutionRunFolder(payload: EvolutionActionPayload): Promise<void> {
	const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
	if (!workspaceRoot) {
		vscode.window.showErrorMessage('No workspace open.');
		return;
	}

	const validated = validatePayload(payload, workspaceRoot);
	if (!validated) { return; }
	const { bigraphAbs, activeRules } = validated;

	const setup = createEvolutionFolderStructure(workspaceRoot, payload.evolutionLabel);

	copyOriginalBigraphToCheckpoints(bigraphAbs, setup);

	const evolutionRules = copyRuleFiles(activeRules, setup.rulesDir, workspaceRoot);
	if (evolutionRules.length === 0) {
		vscode.window.showErrorMessage('No rule files could be copied.');
		return;
	}

	const rawVerification = Array.isArray(payload.verificationBigraphs) ? payload.verificationBigraphs : [];
	const evolutionVerification = copyVerificationFiles(rawVerification, setup.verificationDir, workspaceRoot);

	createWorkspaceBigraphCopy(setup);
	writeEvolutionJson(setup.evolutionFolder, payload.evolutionLabel, evolutionRules, evolutionVerification);

	// Notify the Evolutions list panel that a new folder has appeared
	const { refreshEvolutionsList } = await import('./init.js');
	refreshEvolutionsList();

	// Lock the provider into this evolution by loading the freshly-created evolution.json.
	// This re-populates all form state (including rules with their copied paths inside
	// the evolution folder), so subsequent Play/Step clicks route to dispatchEvolutionRunForExisting
	// and use the correct rule paths.
	const { getEvolutionManagerViewProvider: getProvider } = await import('./evolutionManagerViewProvider.js');
	getProvider()?.setEvolutionFolder(setup.evolutionFolder);

	const clientId = await focusBigraphAndResolveClientId(setup.workspaceBigraphDest)
		?? EditorProvider.getClientIdForFsPath(bigraphAbs);

	await notifyEvolutionManagerBigraphChanged(setup.workspaceBigraphDest);

	try {
		await dispatchToGlsp({
			actionType: payload.actionType,
			rules: evolutionRules,
			verificationBigraphs: evolutionVerification,
			evolutionFolder: setup.evolutionFolder,
			checkpointsDir: setup.checkpointsDir,
			maxOperationsEnabled: payload.maxOperationsEnabled,
			maxOperations: payload.maxOperations,
			checkpointFileGeneration: payload.checkpointFileGeneration,
			visualizeIntermediateSteps: payload.visualizeIntermediateSteps,
			clientId: clientId ?? undefined
		});
		vscode.window.showInformationMessage(`Evolution project created: ${path.basename(setup.evolutionFolder)}`);
	} catch (e) {
		const msg = e instanceof Error ? e.message : String(e);
		vscode.window.showErrorMessage(`Failed to dispatch evolution action: ${msg}`);
	}
}
