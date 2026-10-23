import * as path from 'path';
import * as vscode from 'vscode';
import { getClientIdForFsPath, getPanelForClientId } from '../../editor/glspClientRegistry.js';
import type { ExtensionSidePanel, ExtensionSidePanelView } from '../types';

/** What the diagram webview reports (see PoppAnalysisController#postToHost); opaque to the host. */
export interface PoppSidebarState {
    active: boolean;
    /** A coverage mode name, see {@code MODES} in {@code extension-sidebar/popp/coverage.js}. */
    mode: string;
    validation?: unknown;
    report?: unknown;
    gaps?: unknown;
    gapsActive: boolean;
}

/** The host's side panel view (see {@code sidePanel.ts}): it shows the side panel of the focused diagram's extension. */
const SIDE_PANELS_VIEW_ID = 'bigraph.extensionSidePanels';

/** The last state each diagram webview reported, by GLSP client id. */
const states = new Map<string, PoppSidebarState>();
/** The open panel, while the host shows it. */
let view: ExtensionSidePanelView | undefined;

/**
 * Side panel with the coverage report, the coverage gaps and the validation results of the focused POPP diagram.
 *
 * <p>The host shows it in the Bigraph Explorer sidebar while a POPP diagram is the focused editor and tells it which
 * diagram that is. The panel holds no logic of its own: each diagram webview posts its state whenever it changes
 * ({@link setPoppSidebarState}), and the panel's buttons go back to the focused diagram as commands, which runs the
 * requests against its GLSP session. Layout and script are in {@code extension-sidebar/popp/}.</p>
 */
export const poppCoveragePanel: ExtensionSidePanel = {
    id: 'popp.coverage',
    htmlFile: 'extension-sidebar/popp/coverage.html',
    resolve(panel: ExtensionSidePanelView): vscode.Disposable {
        view = panel;
        return vscode.Disposable.from(
            panel.onDidReceiveMessage(message => onPanelMessage(panel, message as Record<string, unknown>)),
            panel.onDidChangeDiagram(() => render()),
            new vscode.Disposable(() => {
                if (view === panel) {
                    view = undefined;
                }
            }),
        );
    },
};

/** From a diagram webview. */
export function setPoppSidebarState(clientId: string, state: PoppSidebarState): void {
    states.set(clientId, state);
    if (view && shownClientId(view) === clientId) {
        render();
    }
}

/** Brings the side panel to the front, e.g. from the diagram's toolbar. */
export async function revealPoppSidebar(): Promise<void> {
    await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
    await vscode.commands.executeCommand(`${SIDE_PANELS_VIEW_ID}.focus`);
}

/** The GLSP client of the diagram the panel is following. */
function shownClientId(panel: ExtensionSidePanelView): string | undefined {
    return getClientIdForFsPath(panel.diagram.fsPath);
}

function render(): void {
    const panel = view;
    if (!panel) {
        return;
    }
    for (const clientId of [...states.keys()]) {
        if (!getPanelForClientId(clientId)) {
            states.delete(clientId); // the diagram was closed
        }
    }
    const clientId = shownClientId(panel);
    const state = clientId ? states.get(clientId) : undefined;
    void panel.postMessage({
        type: 'state',
        fileName: path.basename(panel.diagram.fsPath),
        state: state?.active ? state : null
    });
}

/** From the panel: {@code ready} once its script has loaded, {@code command} for the focused diagram. */
function onPanelMessage(panel: ExtensionSidePanelView, message: Record<string, unknown>): void {
    if (message.type === 'ready') {
        render();
        return;
    }
    if (message.type !== 'command') {
        return;
    }
    const clientId = shownClientId(panel);
    const diagram = clientId ? getPanelForClientId(clientId) : undefined;
    if (!diagram) {
        return;
    }
    void diagram.webview.postMessage({ ...message, type: 'popp.sidebarCommand' });
    if (message.command === 'focus') {
        diagram.reveal(diagram.viewColumn, false);
    }
}
