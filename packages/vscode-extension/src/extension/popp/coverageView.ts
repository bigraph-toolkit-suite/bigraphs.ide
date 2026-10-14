import * as vscode from 'vscode';
import * as path from 'path';
import { getActiveClientInfo, getPanelForClientId } from '../../editor/glspClientRegistry.js';

export const POPP_COVERAGE_VIEW_ID = 'poppCoverageView';
/** Context key: the active diagram shows the POPP variant (shows the view). */
const CONTEXT_KEY = 'bigraph.poppActive';

/** What the diagram webview reports (see PoppAnalysisController#postToHost); opaque to the host. */
export interface PoppSidebarState {
    active: boolean;
    mode: 'PLANNING' | 'VERIFY';
    validation?: unknown;
    report?: unknown;
    gaps?: unknown;
    gapsActive: boolean;
}

/**
 * Sidebar view with the coverage report, the coverage gaps and the validation results of the active POPP diagram.
 *
 * <p>The view holds no logic of its own: each diagram webview posts its state here whenever it changes, and the
 * view's buttons go back to the active diagram as commands, which runs the requests against its GLSP session.</p>
 */
export class PoppCoverageViewProvider implements vscode.WebviewViewProvider {
    private view?: vscode.WebviewView;
    private readonly states = new Map<string, PoppSidebarState>();
    private shownClientId?: string;

    resolveWebviewView(view: vscode.WebviewView): void {
        this.view = view;
        view.webview.options = { enableScripts: true };
        view.webview.html = renderHtml(view.webview);
        view.webview.onDidReceiveMessage((msg: Record<string, unknown>) => this.onViewMessage(msg));
        view.onDidChangeVisibility(() => {
            if (view.visible) this.render();
        });
        view.onDidDispose(() => { this.view = undefined; });
        this.render();
    }

    /** From a diagram webview. */
    setState(clientId: string, state: PoppSidebarState): void {
        this.states.set(clientId, state);
        this.render();
    }

    /** Re-renders for whichever diagram is active now (tab switches, closed editors). */
    render(): void {
        const active = getActiveClientInfo();
        const state = active ? this.states.get(active.clientId) : undefined;
        void vscode.commands.executeCommand('setContext', CONTEXT_KEY, !!state?.active);
        if (!this.view) {
            return;
        }
        this.shownClientId = state?.active ? active?.clientId : undefined;
        void this.view.webview.postMessage({
            type: 'state',
            fileName: active ? path.basename(active.fsPath) : null,
            state: state?.active ? state : null
        });
    }

    forget(clientId: string): void {
        this.states.delete(clientId);
        this.render();
    }

    /** From the sidebar: forward to the diagram it is showing. */
    private onViewMessage(msg: Record<string, unknown>): void {
        if (msg.type === 'ready') {
            this.render();
            return;
        }
        if (msg.type !== 'command' || !this.shownClientId) {
            return;
        }
        const panel = getPanelForClientId(this.shownClientId);
        void panel?.webview.postMessage({ ...msg, type: 'popp.sidebarCommand' });
        if (msg.command === 'focus') {
            panel?.reveal(panel.viewColumn, false);
        }
    }
}

let provider: PoppCoverageViewProvider | undefined;

export function getPoppCoverageViewProvider(): PoppCoverageViewProvider | undefined {
    return provider;
}

export function initPoppSidebar(): vscode.Disposable[] {
    provider = new PoppCoverageViewProvider();
    const p = provider;
    void vscode.commands.executeCommand('setContext', CONTEXT_KEY, false);
    return [
        vscode.window.registerWebviewViewProvider(POPP_COVERAGE_VIEW_ID, p, { webviewOptions: { retainContextWhenHidden: true } }),
        vscode.window.tabGroups.onDidChangeTabs(() => p.render()),
        vscode.window.tabGroups.onDidChangeTabGroups(() => p.render()),
    ];
}

export async function revealPoppSidebar(): Promise<void> {
    await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
    await vscode.commands.executeCommand(`${POPP_COVERAGE_VIEW_ID}.focus`);
}

function nonce(): string {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    return Array.from({ length: 32 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
}

function renderHtml(webview: vscode.Webview): string {
    const n = nonce();
    return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; script-src 'nonce-${n}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
    body { padding: 0 10px 12px; font-size: 12px; color: var(--vscode-foreground); }
    .empty { opacity: .7; padding: 12px 0; }
    .bar { display: flex; align-items: center; gap: 6px; padding: 8px 0 4px; flex-wrap: wrap; }
    .file { font-weight: 600; flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .seg { display: inline-flex; border: 1px solid var(--vscode-panel-border, rgba(128,128,128,.4)); border-radius: 4px; overflow: hidden; }
    .seg button { border: 0; border-radius: 0; }
    button { font: inherit; color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground);
             border: 1px solid transparent; border-radius: 3px; padding: 2px 8px; cursor: pointer; }
    button:hover { background: var(--vscode-button-secondaryHoverBackground); }
    button.on { color: var(--vscode-button-foreground); background: var(--vscode-button-background); }
    h3 { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; opacity: .8; margin: 14px 0 4px;
         display: flex; justify-content: space-between; align-items: center; }
    .row { padding: 3px 6px; border-radius: 3px; display: flex; gap: 6px; align-items: baseline; }
    .row.click { cursor: pointer; }
    .row.click:hover { background: var(--vscode-list-hoverBackground); }
    .row .badge { flex: 0 0 auto; font-size: 10px; padding: 0 4px; border-radius: 3px; border: 1px solid currentColor; }
    .row .text { flex: 1 1 auto; overflow-wrap: anywhere; }
    .muted { opacity: .7; }
    .covered { color: var(--vscode-testing-iconPassed, #3fb950); }
    .partial, .warning { color: var(--vscode-editorWarning-foreground, #d29922); }
    .uncovered, .error { color: var(--vscode-editorError-foreground, #f85149); }
    .sub { font-size: 11px; opacity: .75; }
</style>
</head>
<body>
<div id="root"><div class="empty">Open a POPP diagram to see its coverage and validation.</div></div>
<script nonce="${n}">
(function () {
    const vscode = acquireVsCodeApi();
    const root = document.getElementById('root');
    const send = (command, extra) => vscode.postMessage(Object.assign({ type: 'command', command }, extra || {}));

    // Every user-written text goes through textContent, never innerHTML.
    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }
    function row(parent, badge, badgeCls, text, ids, sub) {
        const r = el('div', 'row' + (ids && ids.length ? ' click' : ''));
        if (badge) r.appendChild(el('span', 'badge ' + (badgeCls || ''), badge));
        const t = el('span', 'text', text);
        if (sub) { t.appendChild(document.createElement('br')); t.appendChild(el('span', 'sub', sub)); }
        r.appendChild(t);
        if (ids && ids.length) { r.title = 'Show in diagram'; r.onclick = () => send('focus', { ids }); }
        parent.appendChild(r);
    }
    function section(title, right) {
        const h = el('h3', null, title);
        if (right) h.appendChild(right);
        root.appendChild(h);
    }

    function render(fileName, s) {
        root.replaceChildren();
        if (!s) {
            root.appendChild(el('div', 'empty', 'Open a POPP diagram to see its coverage and validation.'));
            return;
        }
        const bar = el('div', 'bar');
        bar.appendChild(el('span', 'file', fileName || 'POPP model'));
        const seg = el('span', 'seg');
        ['PLANNING', 'VERIFY'].forEach(m => {
            const b = el('button', s.mode === m ? 'on' : '', m === 'PLANNING' ? 'Planning' : 'Verify');
            b.title = m === 'PLANNING' ? 'Every success criterion counts' : 'Only criteria validated by a produced proof count';
            b.onclick = () => send('setMode', { mode: m });
            seg.appendChild(b);
        });
        bar.appendChild(seg);
        const refresh = el('button', null, 'Refresh');
        refresh.onclick = () => send('refresh');
        bar.appendChild(refresh);
        root.appendChild(bar);

        // Validation
        const v = s.validation;
        section('Validation');
        if (!v) {
            row(root, null, null, 'Validating\\u2026');
        } else if (v.issues.length === 0) {
            row(root, 'OK', 'covered', 'The model is structurally valid', null,
                v.patternsMatched + ' patterns matched, ' + v.patternsSkipped + ' skipped, ' + v.durationMs + ' ms');
        } else {
            v.issues.forEach(i => {
                const sev = String(i.severity) === 'ERROR' ? 'error' : 'warning';
                row(root, sev, sev, String(i.message || ''), i.nodeIds || [],
                    String(i.code || '').toLowerCase().replace(/_/g, ' '));
            });
            const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
            row(root, null, null, '', null, plural(v.errors, 'error') + ', ' + plural(v.warnings, 'warning') + ' \\u00b7 '
                + v.patternsMatched + ' patterns matched, ' + v.durationMs + ' ms');
        }

        // Gaps
        const gaps = s.gaps || [];
        const toggle = el('button', s.gapsActive ? 'on' : '', s.gapsActive ? 'Highlighted' : 'Highlight');
        toggle.title = 'Highlight uncovered nodes in the diagram';
        toggle.onclick = () => send('setGaps', { active: !s.gapsActive });
        section('Uncovered (' + gaps.length + ')', toggle);
        const roots = gaps.filter(g => g.rootCause);
        if (gaps.length === 0) {
            row(root, null, null, s.report ? 'Everything that can be covered is covered' : 'Computing\\u2026', null);
        }
        roots.forEach(g => row(root, g.coverage === 'PARTIAL' ? 'partial' : 'uncovered', g.coverage.toLowerCase(),
            g.label, [g.nodeId], g.message ? g.message : undefined));
        const affected = gaps.filter(g => !g.rootCause);
        if (affected.length > 0) {
            row(root, null, null, affected.length + ' more node(s) are uncovered only because of the ones above',
                affected.map(g => g.nodeId));
        }

        // Report
        const r = s.report;
        if (!r) return;
        section('Tree coverage');
        if (r.roots.length === 0) row(root, null, null, 'No coverable trees yet');
        r.roots.forEach(t => row(root, t.coverage.toLowerCase(), t.coverage.toLowerCase(),
            t.kind.toLowerCase().replace(/_/g, ' ') + ': ' + (t.description || t.id), [t.id]));
    
        section('Size');
        Object.entries(r.trees).forEach(([kind, m]) => row(root, null, null,
            kind.toLowerCase().replace(/_/g, ' ') + ': ' + m.nodes + ' nodes',
            null, 'trees ' + m.roots + ' \\u00b7 height ' + m.height + ' \\u00b7 width ' + m.width));
    }

    window.addEventListener('message', e => {
        if (e.data && e.data.type === 'state') render(e.data.fileName, e.data.state);
    });
    vscode.postMessage({ type: 'ready' });
})();
</script>
</body>
</html>`;
}
