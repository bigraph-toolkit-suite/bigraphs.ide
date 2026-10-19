import * as vscode from 'vscode';
import * as path from 'path';
import { getActiveClientInfo, getPanelForClientId } from '../../editor/glspClientRegistry.js';

export const POPP_COVERAGE_VIEW_ID = 'poppCoverageView';
/** Context key: the active diagram shows the POPP variant (shows the view). */
const CONTEXT_KEY = 'bigraph.poppActive';

/** What the diagram webview reports (see PoppAnalysisController#postToHost); opaque to the host. */
export interface PoppSidebarState {
    active: boolean;
    /** A coverage mode name, see {@link COVERAGE_MODES}. */
    mode: string;
    validation?: unknown;
    report?: unknown;
    gaps?: unknown;
    gapsActive: boolean;
}

/**
 * The coverage modes for the dropdown and the coverage statements; the same list as {@code POPP_COVERAGE_MODES} in the webview and the server's
 * {@code CoverageMode.presets()} (the sidebar script cannot import webview modules).
 */
const COVERAGE_MODES: ReadonlyArray<{ id: string; label: string; description: string; by: string }> = [
    { id: 'PROBLEMS_BY_GOALS', label: 'Problems by goals', description: 'Every goal counts', by: 'by the goal tree' },
    { id: 'PROBLEMS_BY_PROOFS', label: 'Problems by proofs', description: 'Every success proof counts, through criteria and goals',
      by: 'by the success proof tree' },
    { id: 'GOALS_BY_CRITERIA', label: 'Goals by criteria', description: 'Every success criterion counts', by: 'by the success criteria tree' },
    { id: 'PLANNING', label: 'Planning', description: 'Whole model; every success criterion counts', by: 'when planning' },
    { id: 'VERIFY', label: 'Verify', description: 'Whole model; only criteria validated by a proof that a solution produces count',
      by: 'when verifying' }
];

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
    select { font: inherit; color: var(--vscode-dropdown-foreground); background: var(--vscode-dropdown-background);
             border: 1px solid var(--vscode-dropdown-border, transparent); border-radius: 2px; padding: 1px 4px; }
    .mode { border-left: 2px solid transparent; border-radius: 0 3px 3px 0; }
    .mode.current { border-left-color: var(--vscode-focusBorder); background: var(--vscode-list-inactiveSelectionBackground); }
    .mode .text b { font-weight: 600; }
    .trees { margin: 0 0 4px 14px; }
    .trees .row { padding: 1px 6px; }
</style>
</head>
<body>
<div id="root"><div class="empty">Open a POPP diagram to see its coverage and validation.</div></div>
<script nonce="${n}">
(function () {
    const vscode = acquireVsCodeApi();
    const root = document.getElementById('root');
    const send = (command, extra) => vscode.postMessage(Object.assign({ type: 'command', command }, extra || {}));
    const MODES = ${JSON.stringify(COVERAGE_MODES)};

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
        const select = el('select');
        select.title = 'Coverage mode: what counts as covered by itself, and which nodes the gaps are reported for';
        MODES.forEach(m => {
            const o = el('option', null, m.label);
            o.value = m.id;
            o.title = m.description;
            select.appendChild(o);
        });
        select.value = s.mode;
        select.onchange = () => send('setMode', { mode: select.value });
        bar.appendChild(select);
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

        // Tree coverage: one statement per mode; the selected one lists its trees. A click selects the mode.
        const r = s.report;
        section('Tree coverage');
        if (!r) {
            row(root, null, null, 'Computing\\u2026');
        } else {
            (r.modes || []).forEach(m => modeStatement(m, m.mode === s.mode ? r.roots : null));
        }

        // Gaps of the selected mode
        const gaps = s.gaps || [];
        const toggle = el('button', s.gapsActive ? 'on' : '', s.gapsActive ? 'Highlighted' : 'Highlight');
        toggle.title = 'Highlight uncovered nodes in the diagram';
        toggle.onclick = () => send('setGaps', { active: !s.gapsActive });
        section('Uncovered (' + gaps.length + ')', toggle);
        const roots = gaps.filter(g => g.rootCause);
        if (gaps.length === 0) {
            row(root, null, null, r ? 'Everything this mode asks about is covered' : 'Computing\\u2026', null);
        }
        roots.forEach(g => row(root, g.coverage === 'PARTIAL' ? 'partial' : 'uncovered', g.coverage.toLowerCase(),
            g.label, [g.nodeId], g.message ? g.message : undefined));
        const affected = gaps.filter(g => !g.rootCause);
        if (affected.length > 0) {
            row(root, null, null, affected.length + ' more node(s) are uncovered only because of the ones above',
                affected.map(g => g.nodeId));
        }

        if (!r) return;
        section('Depth and breadth');
        Object.entries(r.trees).forEach(([kind, m]) => row(root, null, null,
            kindName(kind) + ': ' + m.nodes + ' nodes',
            null, 'trees ' + m.roots + ' \\u00b7 depth ' + m.height + ' \\u00b7 breadth ' + m.width));
    }

    const kindName = k => String(k || '').toLowerCase().replace(/_/g, ' ');
    const VERB = { COVERED: 'is covered', PARTIAL: 'is partially covered', UNCOVERED: 'is not covered' };

    // e.g. "Problem tree is partially covered by the goal tree", "Model is covered when planning"
    function modeStatement(m, trees) {
        const info = MODES.find(x => x.id === m.mode) || { label: m.mode, by: '', description: '' };
        const cov = m.coverage ? String(m.coverage) : null;
        const subject = m.kind ? kindName(m.kind).replace(/^./, c => c.toUpperCase()) + ' tree' : 'Model';
        const r = el('div', 'row click mode' + (trees ? ' current' : ''));
        r.appendChild(el('span', 'badge ' + (cov ? cov.toLowerCase() : 'muted'), cov ? cov.toLowerCase() : 'empty'));
        const t = el('span', 'text');
        if (cov) {
            t.appendChild(document.createTextNode(subject + ' '));
            t.appendChild(el('b', null, VERB[cov]));
            t.appendChild(document.createTextNode(' ' + info.by));
        } else {
            t.appendChild(document.createTextNode((m.kind ? 'No ' + kindName(m.kind) + ' tree' : 'No coverable trees') + ' yet (' + info.label + ')'));
        }
        const c = m.counts || {};
        const total = (c.COVERED || 0) + (c.PARTIAL || 0) + (c.UNCOVERED || 0);
        if (total > 0) {
            t.appendChild(document.createElement('br'));
            t.appendChild(el('span', 'sub', (c.COVERED || 0) + ' of ' + total + ' ' + (m.kind ? kindName(m.kind) : 'node')
                + ' nodes covered' + (c.PARTIAL ? ', ' + c.PARTIAL + ' partially' : '')));
        }
        r.appendChild(t);
        r.title = info.label + ': ' + info.description + (trees ? '' : ' (click to show in the diagram)');
        if (!trees) r.onclick = () => send('setMode', { mode: m.mode });
        root.appendChild(r);
        if (trees && trees.length) {
            const list = el('div', 'trees');
            trees.forEach(tr => row(list, tr.coverage.toLowerCase(), tr.coverage.toLowerCase(),
                kindName(tr.kind) + ' tree \\u201c' + (tr.description || tr.id) + '\\u201d', [tr.id]));
            root.appendChild(list);
        }
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
