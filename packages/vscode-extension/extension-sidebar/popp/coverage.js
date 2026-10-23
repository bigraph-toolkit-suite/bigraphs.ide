// Script of the POPP side panel (see src/extension/popp/coveragePanel.ts). It renders the state of the focused POPP
// diagram, which the host posts as {type: 'state', fileName, state}, and sends the buttons back as {type: 'command'}.
(function () {
    const vscode = acquireVsCodeApi();
    const root = document.getElementById('root');
    const send = (command, extra) => vscode.postMessage(Object.assign({ type: 'command', command }, extra || {}));
    // The coverage modes for the dropdown and the coverage statements: the same list as POPP_COVERAGE_MODES in the
    // diagram webview and the server's CoverageMode.presets().
    const MODES = [
        { id: 'PROBLEMS_BY_GOALS', label: 'Problems by goals', description: 'Every goal counts', by: 'by the goal tree' },
        { id: 'PROBLEMS_BY_PROOFS', label: 'Problems by proofs', description: 'Every success proof counts, through criteria and goals',
          by: 'by the success proof tree' },
        { id: 'GOALS_BY_CRITERIA', label: 'Goals by criteria', description: 'Every success criterion counts', by: 'by the success criteria tree' },
        { id: 'PLANNING', label: 'Planning', description: 'Whole model; every success criterion counts', by: 'when planning' },
        { id: 'VERIFY', label: 'Verify', description: 'Whole model; only criteria validated by a proof that a solution produces count',
          by: 'when verifying' }
    ];

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
            row(root, null, null, 'Validating\u2026');
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
            row(root, null, null, '', null, plural(v.errors, 'error') + ', ' + plural(v.warnings, 'warning') + ' \u00b7 '
                + v.patternsMatched + ' patterns matched, ' + v.durationMs + ' ms');
        }

        // Tree coverage: one statement per mode; the selected one lists its trees. A click selects the mode.
        const r = s.report;
        section('Tree coverage');
        if (!r) {
            row(root, null, null, 'Computing\u2026');
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
            row(root, null, null, r ? 'Everything this mode asks about is covered' : 'Computing\u2026', null);
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
            null, 'trees ' + m.roots + ' \u00b7 depth ' + m.height + ' \u00b7 breadth ' + m.width));
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
                kindName(tr.kind) + ' tree \u201c' + (tr.description || tr.id) + '\u201d', [tr.id]));
            root.appendChild(list);
        }
    }

    window.addEventListener('message', e => {
        if (e.data && e.data.type === 'state') render(e.data.fileName, e.data.state);
    });
    vscode.postMessage({ type: 'ready' });
})();
