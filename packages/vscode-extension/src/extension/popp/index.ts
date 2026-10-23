import * as path from 'path';
import * as vscode from 'vscode';
import { getPanelForClientId } from '../../editor/glspClientRegistry';
import { IClientExtension, ModelVariantDescriptor, WebviewMessageContext } from '../types';
import { PoppSidebarState, poppCoveragePanel, revealPoppSidebar, setPoppSidebarState } from './coveragePanel';

export const POPP_VARIANT_ID = 'popp';

const POPP_VARIANT: ModelVariantDescriptor = {
    id: POPP_VARIANT_ID,
    label: 'Problem-Oriented Project Planning',
    description: 'Problem-Oriented Project Planning encoded as a bigraph',
    iconCodicon: 'tasklist',
    fileNameBase: 'new-popp-model',
    writesMetaFile: true,
};

export const problemOrientedProjectPlanningExtension: IClientExtension = {
    id: 'popp',
    name: 'Problem-Oriented Project Planning',
    variants: [POPP_VARIANT],
    sidePanel: poppCoveragePanel,

    /** Relays between the diagram webview and the POPP side panel, and the JSON file dialogs. */
    handleWebviewMessage(context: WebviewMessageContext): boolean {
        const { message, clientId } = context;
        if (message.type === 'popp.sidebarState') {
            setPoppSidebarState(clientId, message.state as PoppSidebarState);
            return true;
        }
        if (message.type === 'popp.openSidebar') {
            void revealPoppSidebar();
            return true;
        }
        if (message.type === 'popp.saveJson' && typeof message.json === 'string') {
            void saveJson(context.filePath, message.json, String(message.fileName ?? 'popp-model.popp.json'));
            return true;
        }
        if (message.type === 'popp.importJsonRequest') {
            void importJson(clientId, context.filePath);
            return true;
        }
        return false;
    },
};

const JSON_FILTERS = { 'POPP JSON': ['json'] };

/** Asks where to save an exported POPP JSON document (next to the diagram file by default) and writes it. */
async function saveJson(diagramPath: string, json: string, fileName: string): Promise<void> {
    const target = await vscode.window.showSaveDialog({
        defaultUri: vscode.Uri.file(path.join(path.dirname(diagramPath), fileName)),
        filters: JSON_FILTERS,
        title: 'Export POPP model as JSON'
    });
    if (!target) {
        return;
    }
    await vscode.workspace.fs.writeFile(target, Buffer.from(json, 'utf8'));
    void vscode.window.showInformationMessage(`POPP model exported to ${path.basename(target.fsPath)}`);
}

/** Lets the user pick a POPP JSON file and sends its content to the diagram, which replaces its model with it. */
async function importJson(clientId: string, diagramPath: string): Promise<void> {
    const picked = await vscode.window.showOpenDialog({
        defaultUri: vscode.Uri.file(path.dirname(diagramPath)),
        filters: JSON_FILTERS,
        canSelectMany: false,
        title: 'Import POPP model from JSON'
    });
    if (!picked || picked.length === 0) {
        return;
    }
    const replace = 'Replace';
    const answer = await vscode.window.showWarningMessage(
        `Replace the POPP model in ${path.basename(diagramPath)} with ${path.basename(picked[0].fsPath)}?`,
        { modal: true, detail: 'The current nodes and relations are removed. Undo by closing the diagram without saving.' },
        replace
    );
    if (answer !== replace) {
        return;
    }
    const json = Buffer.from(await vscode.workspace.fs.readFile(picked[0])).toString('utf8');
    await getPanelForClientId(clientId)?.webview.postMessage({ type: 'popp.importJsonFile', json });
}
