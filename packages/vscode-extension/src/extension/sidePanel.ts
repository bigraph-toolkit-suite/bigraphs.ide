import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { BIGRAPH_VARIANT_ID } from './core';
import { getClientExtensions } from './extensions';
import type { ExtensionSidePanel, ExtensionSidePanelDiagram, ExtensionSidePanelView } from './types';

/**
 * VS Code only loads sidebar views that are declared in package.json.
 * One contributed view shows the side panel of the extension that owns
 * the focused diagram. The view lives in the Bigraph Explorer sidebar.
 * Switching diagrams leaves the activity bar on its current item.
 * At most one panel is visible.
 */
const SIDE_PANELS_VIEW_ID = 'bigraph.extensionSidePanels';
const SIDE_PANELS_VISIBLE = 'bigraph.extensionSidePanels.visible';
const DIAGRAM_VIEW_TYPE = 'bigraph.glspDiagram';

interface LivePanel {
    readonly extensionId: string;
    /** Shown as the panel title. Always {@link IClientExtension.name}. */
    readonly extensionName: string;
    readonly panel: ExtensionSidePanel;
    /** Variant ids whose focused diagram should show this panel. */
    readonly variantIds: ReadonlySet<string>;
    surface?: HostedSidePanel;
    resolveDisposable?: vscode.Disposable;
}

const livePanels: LivePanel[] = [];
/** The panel for the focused diagram, if that extension registered one. */
let current: LivePanel | undefined;
/** Diagram the visible panel is following, including when no panel matches. */
let currentDiagram: ExtensionSidePanelDiagram | undefined;
let session: SidePanelSession | undefined;
let syncChain: Promise<void> = Promise.resolve();
let metaWatch: vscode.Disposable | undefined;
let watchedMetaPath: string | undefined;
let metaWatchTimer: ReturnType<typeof setTimeout> | undefined;

function isDisposable(value: unknown): value is vscode.Disposable {
    return typeof value === 'object'
        && value !== null
        && typeof (value as vscode.Disposable).dispose === 'function';
}

function escapeHtml(value: string): string {
    return value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function scheduleSync(): void {
    syncChain = syncChain.then(() => syncFocusedDiagram()).catch(err => {
        console.error('[bigraphide] Side panel sync failed:', err);
    });
}

/** XMI path of the focused editor tab. Signature files are skipped. */
function focusedDiagramPath(): string | undefined {
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
    if (!tab) {
        return undefined;
    }
    const fsPath = tabFsPath(tab);
    if (!fsPath) {
        return undefined;
    }
    const lower = fsPath.toLowerCase();
    if (!lower.endsWith('.xmi') || lower.endsWith('.signature.xmi')) {
        return undefined;
    }
    return fsPath;
}

function tabFsPath(tab: vscode.Tab): string | undefined {
    const input = tab.input;
    if (input instanceof vscode.TabInputCustom && input.viewType === DIAGRAM_VIEW_TYPE) {
        return input.uri.fsPath;
    }
    if (input instanceof vscode.TabInputText) {
        return input.uri.fsPath;
    }
    return undefined;
}

function siblingMetaPath(xmiFsPath: string): string {
    return xmiFsPath.replace(/\.xmi$/i, '.bigraph-meta');
}

/**
 * Variant id stored as {@code modelType} on the root element of the
 * sibling {@code .bigraph-meta} file. A missing file is a classic
 * bigraph until {@link watchMetaFile} sees it appear.
 */
function readModelType(xmiFsPath: string): string {
    try {
        const text = fs.readFileSync(siblingMetaPath(xmiFsPath), 'utf8');
        const modelType = modelTypeFromMeta(text);
        if (modelType) {
            return modelType;
        }
    } catch {
        // Missing or unreadable meta file: treat as a classic bigraph.
    }
    return BIGRAPH_VARIANT_ID;
}

/**
 * {@code modelType} on the root opening tag. EMF writes the attribute
 * there, so a nested {@code modelType} in extension data is ignored.
 */
function modelTypeFromMeta(text: string): string | undefined {
    const withoutDecl = text.replace(/^\uFEFF?/, '').replace(/<\?xml[\s\S]*?\?>/, '');
    const open = /<([\w:.-]+)([^>]*)>/.exec(withoutDecl);
    if (!open) {
        return undefined;
    }
    const attr = /(?:^|\s)(?:[\w.-]+:)?modelType\s*=\s*"([^"]*)"/.exec(open[2] ?? '');
    const value = attr?.[1].trim();
    return value ? value : undefined;
}

function sameDiagram(
    a: ExtensionSidePanelDiagram | undefined,
    b: ExtensionSidePanelDiagram | undefined
): boolean {
    return !!a && !!b && a.fsPath === b.fsPath && a.modelType === b.modelType;
}

/** Literal glob for one meta file name. */
function globLiteral(value: string): string {
    return value.replace(/[\\*?[\]{}]/g, match => `\\${match}`);
}

/**
 * Follows the focused diagram's meta file. A create, change, or delete
 * syncs again, so a meta file written after the tab is focused still
 * selects the right panel.
 */
function watchMetaFile(xmiFsPath: string | undefined): void {
    const metaPath = xmiFsPath ? siblingMetaPath(xmiFsPath) : undefined;
    if (watchedMetaPath === metaPath) {
        return;
    }
    metaWatch?.dispose();
    metaWatch = undefined;
    watchedMetaPath = metaPath;
    if (!metaPath) {
        return;
    }
    const watcher = vscode.workspace.createFileSystemWatcher(
        new vscode.RelativePattern(path.dirname(metaPath), globLiteral(path.basename(metaPath)))
    );
    const kick = () => scheduleMetaSync();
    metaWatch = vscode.Disposable.from(
        watcher,
        watcher.onDidCreate(kick),
        watcher.onDidChange(kick),
        watcher.onDidDelete(kick),
    );
}

function scheduleMetaSync(): void {
    if (metaWatchTimer) {
        clearTimeout(metaWatchTimer);
    }
    metaWatchTimer = setTimeout(() => {
        metaWatchTimer = undefined;
        scheduleSync();
    }, 100);
}

function disposeMetaWatch(): void {
    if (metaWatchTimer) {
        clearTimeout(metaWatchTimer);
        metaWatchTimer = undefined;
    }
    metaWatch?.dispose();
    metaWatch = undefined;
    watchedMetaPath = undefined;
}

/**
 * Shows the panel of the extension that owns the focused diagram.
 * Any other panel is hidden. A focused file with no registered panel
 * hides the sidebar view. The activity bar is left on its current item.
 * The same panel stays mounted when the user switches between two
 * diagrams it owns, and receives the new {@link ExtensionSidePanelDiagram}.
 */
async function syncFocusedDiagram(): Promise<void> {
    const fsPath = focusedDiagramPath();
    watchMetaFile(fsPath);
    const modelType = fsPath ? readModelType(fsPath) : undefined;
    const next = modelType
        ? livePanels.find(entry => entry.variantIds.has(modelType))
        : undefined;
    const diagram = fsPath && modelType ? { fsPath, modelType } : undefined;

    if (next === current) {
        if (!sameDiagram(currentDiagram, diagram)) {
            currentDiagram = diagram;
            if (current?.surface && diagram) {
                current.surface.setDiagram(diagram);
            }
        }
        return;
    }

    const previous = current;
    const previousDiagram = currentDiagram;
    current = next;
    currentDiagram = diagram;
    try {
        await vscode.commands.executeCommand('setContext', SIDE_PANELS_VISIBLE, next !== undefined);
        if (next && diagram) {
            await session?.show(next, diagram);
        }
    } catch (err) {
        current = previous;
        currentDiagram = previousDiagram;
        throw err;
    }
}

/**
 * Registers the sidebar host and each extension's {@code sidePanel}.
 * The view stays hidden until the focused diagram belongs to an
 * extension that registered a panel.
 */
export function registerExtensionSidePanels(context: vscode.ExtensionContext): vscode.Disposable[] {
    const seen = new Set<string>();
    for (const owner of getClientExtensions()) {
        const panel = owner.sidePanel;
        if (!panel) {
            continue;
        }
        if (seen.has(panel.id)) {
            throw new Error(`Duplicate side panel id "${panel.id}" (extension "${owner.id}").`);
        }
        seen.add(panel.id);
        livePanels.push({
            extensionId: owner.id,
            extensionName: owner.name,
            panel,
            variantIds: new Set(owner.variants.map(variant => variant.id)),
        });
    }

    const host = new SidePanelSession(context.extensionUri);
    session = host;
    const disposables: vscode.Disposable[] = [
        vscode.window.registerWebviewViewProvider(
            SIDE_PANELS_VIEW_ID,
            host,
            { webviewOptions: { retainContextWhenHidden: true } }
        ),
        vscode.window.tabGroups.onDidChangeTabs(() => scheduleSync()),
        vscode.window.tabGroups.onDidChangeTabGroups(() => scheduleSync()),
        new vscode.Disposable(() => {
            if (session === host) {
                session = undefined;
            }
            disposeMetaWatch();
        }),
    ];
    scheduleSync();
    return disposables;
}

class HostedSidePanel implements ExtensionSidePanelView {
    private htmlValue = '';
    private readonly listeners = new Set<(message: unknown) => void>();
    private readonly diagramListeners = new Set<(diagram: ExtensionSidePanelDiagram) => void>();

    constructor(
        private readonly host: SidePanelSession,
        readonly extensionId: string,
        readonly id: string,
        readonly extensionUri: vscode.Uri,
        private diagramValue: ExtensionSidePanelDiagram
    ) {}

    get diagram(): ExtensionSidePanelDiagram {
        return this.diagramValue;
    }

    setDiagram(diagram: ExtensionSidePanelDiagram): void {
        if (sameDiagram(this.diagramValue, diagram)) {
            return;
        }
        this.diagramValue = diagram;
        for (const listener of [...this.diagramListeners]) {
            try {
                listener(diagram);
            } catch (err) {
                console.error('[bigraphide] Side panel diagram listener failed:', err);
            }
        }
    }

    get html(): string {
        return this.htmlValue;
    }

    set html(value: string) {
        if (value === this.htmlValue) {
            return;
        }
        this.htmlValue = value;
        this.host.onHtmlChanged(this.id);
    }

    asWebviewUri(localResource: vscode.Uri): vscode.Uri {
        return this.host.asWebviewUri(localResource);
    }

    postMessage(message: unknown): Thenable<boolean> {
        return this.host.postMessage(this, message);
    }

    onDidReceiveMessage(listener: (message: unknown) => void): vscode.Disposable {
        this.listeners.add(listener);
        return new vscode.Disposable(() => {
            this.listeners.delete(listener);
        });
    }

    onDidChangeDiagram(listener: (diagram: ExtensionSidePanelDiagram) => void): vscode.Disposable {
        this.diagramListeners.add(listener);
        return new vscode.Disposable(() => {
            this.diagramListeners.delete(listener);
        });
    }

    deliver(message: unknown): void {
        for (const listener of [...this.listeners]) {
            listener(message);
        }
    }
}

class SidePanelSession implements vscode.WebviewViewProvider {
    private webviewView: vscode.WebviewView | undefined;
    private webview: vscode.Webview | undefined;
    private suppressRender = false;
    private showGeneration = 0;

    constructor(private readonly extensionUri: vscode.Uri) {}

    asWebviewUri(localResource: vscode.Uri): vscode.Uri {
        if (!this.webview) {
            throw new Error('Side panel webview is not open yet.');
        }
        return this.webview.asWebviewUri(localResource);
    }

    async resolveWebviewView(
        webviewView: vscode.WebviewView,
        _resolveContext: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken
    ): Promise<void> {
        this.webviewView = webviewView;
        this.webview = webviewView.webview;
        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri],
        };

        const messageSub = webviewView.webview.onDidReceiveMessage(message => {
            current?.surface?.deliver(message);
        });
        webviewView.onDidDispose(() => {
            messageSub.dispose();
            this.webview = undefined;
            this.webviewView = undefined;
            for (const entry of livePanels) {
                entry.resolveDisposable?.dispose();
                entry.resolveDisposable = undefined;
                entry.surface = undefined;
            }
        });

        if (current && currentDiagram) {
            await this.show(current, currentDiagram);
        }
    }

    async show(entry: LivePanel, diagram: ExtensionSidePanelDiagram): Promise<void> {
        const generation = ++this.showGeneration;
        await this.mount(entry, diagram);
        if (generation !== this.showGeneration || current !== entry) {
            return;
        }
        entry.surface?.setDiagram(diagram);
        this.applyTitle(entry);
        this.render(entry);
    }

    onHtmlChanged(panelId: string): void {
        if (this.suppressRender || current?.panel.id !== panelId || !current) {
            return;
        }
        this.render(current);
    }

    postMessage(from: HostedSidePanel, message: unknown): Thenable<boolean> {
        if (!this.webview || current?.surface !== from) {
            return Promise.resolve(false);
        }
        return this.webview.postMessage(message);
    }

    private async mount(entry: LivePanel, diagram: ExtensionSidePanelDiagram): Promise<void> {
        const webview = this.webview;
        if (entry.surface || !webview) {
            return;
        }
        const surface = new HostedSidePanel(
            this,
            entry.extensionId,
            entry.panel.id,
            this.extensionUri,
            diagram
        );
        entry.surface = surface;
        this.suppressRender = true;
        try {
            if (entry.panel.htmlFile) {
                surface.html = loadPanelHtmlFile(this.extensionUri, entry.panel.htmlFile, webview);
            }
            if (entry.panel.resolve) {
                const result = await entry.panel.resolve(surface);
                if (isDisposable(result)) {
                    entry.resolveDisposable = result;
                }
            }
        } catch (err) {
            const message = err instanceof Error ? err.message : String(err);
            console.error(`[bigraphide] Side panel "${entry.panel.id}" failed to resolve:`, err);
            surface.html = `<p>Could not open this panel.</p><pre>${escapeHtml(message)}</pre>`;
        } finally {
            this.suppressRender = false;
        }
    }

    private applyTitle(entry: LivePanel): void {
        if (this.webviewView) {
            this.webviewView.title = entry.extensionName;
        }
    }

    private render(entry: LivePanel): void {
        const webview = this.webview;
        const html = entry.surface?.html ?? '';
        if (!webview) {
            return;
        }
        webview.html = documentHtml(html, webview, createNonce());
    }
}

/**
 * Reads {@link ExtensionSidePanel.htmlFile} and rewrites relative
 * asset URLs so the webview can load scripts and styles next to it.
 */
function loadPanelHtmlFile(extensionUri: vscode.Uri, htmlFile: string, webview: vscode.Webview): string {
    const normalized = htmlFile.replace(/\\/g, '/').replace(/^\/+/, '');
    const fileUri = vscode.Uri.joinPath(extensionUri, ...normalized.split('/'));
    let html: string;
    try {
        html = fs.readFileSync(fileUri.fsPath, 'utf8');
    } catch (err) {
        const detail = err instanceof Error ? err.message : String(err);
        throw new Error(`Cannot read side panel HTML "${htmlFile}": ${detail}`);
    }
    return html.replace(
        /(\s(?:src|href)\s*=\s*)(["'])([^"']+)\2/gi,
        (full, prefix: string, quote: string, url: string) => {
            if (/^[a-z][a-z0-9+.-]*:/i.test(url) || url.startsWith('//') || url.startsWith('#') || url.startsWith('data:')) {
                return full;
            }
            const asset = resolveAgainstHtmlFile(fileUri, url);
            return `${prefix}${quote}${webview.asWebviewUri(asset)}${quote}`;
        }
    );
}

function resolveAgainstHtmlFile(fileUri: vscode.Uri, relative: string): vscode.Uri {
    const directory = fileUri.path.replace(/\/[^/]*$/, '') || '/';
    const segments = directory.split('/').filter(segment => segment.length > 0);
    for (const part of relative.split('/')) {
        if (part.length === 0 || part === '.') {
            continue;
        }
        if (part === '..') {
            segments.pop();
        } else {
            segments.push(part);
        }
    }
    return fileUri.with({ path: `/${segments.join('/')}` });
}

/**
 * Turns an extension HTML file, or a fragment, into the one document
 * this webview shows. Injects the content security policy VS Code
 * requires and a nonce on each script.
 */
function documentHtml(html: string, webview: vscode.Webview, nonce: string): string {
    const csp = [
        "default-src 'none'",
        `img-src ${webview.cspSource} data: https:`,
        `style-src ${webview.cspSource} 'unsafe-inline'`,
        `script-src 'nonce-${nonce}' ${webview.cspSource}`,
        `font-src ${webview.cspSource}`,
    ].join('; ');
    const meta = `<meta http-equiv="Content-Security-Policy" content="${csp}">`;
    let document = html;
    if (/<head[^>]*>/i.test(document)) {
        document = document.replace(/<head[^>]*>/i, match => `${match}${meta}`);
    } else if (/<html[^>]*>/i.test(document)) {
        document = document.replace(/<html[^>]*>/i, match => `${match}<head>${meta}</head>`);
    } else {
        document = `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">${meta}</head><body>${document}</body></html>`;
    }
    return document.replace(/<script\b([^>]*)>/gi, (full, attrs: string) => {
        if (/\bnonce\s*=/i.test(attrs)) {
            return full;
        }
        return `<script nonce="${nonce}"${attrs}>`;
    });
}

function createNonce(): string {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let nonce = '';
    for (let i = 0; i < 32; i++) {
        nonce += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
    }
    return nonce;
}
