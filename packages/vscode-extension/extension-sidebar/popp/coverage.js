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
        const element = document.createElement(tag);
        if (cls) element.className = cls;
        if (text !== undefined) element.textContent = text;
        return element;
    }
    function row(parent, badge, badgeCls, text, ids, sub) {
        const line = el('div', 'row' + (ids && ids.length ? ' click' : ''));
        if (badge) line.appendChild(el('span', 'badge ' + (badgeCls || ''), badge));
        const label = el('span', 'text', text);
        if (sub) { label.appendChild(document.createElement('br')); label.appendChild(el('span', 'sub', sub)); }
        line.appendChild(label);
        if (ids && ids.length) { line.title = 'Show in diagram'; line.onclick = () => send('focus', { ids }); }
        parent.appendChild(line);
    }
    function section(title, right) {
        const heading = el('h3', null, title);
        if (right) heading.appendChild(right);
        root.appendChild(heading);
    }

    function render(fileName, state) {
        root.replaceChildren();
        if (!state) {
            root.appendChild(el('div', 'empty', 'Open a POPP diagram to see its coverage and validation.'));
            return;
        }
        const bar = el('div', 'bar');
        bar.appendChild(el('span', 'file', fileName || 'POPP model'));
        const select = el('select');
        select.title = 'Coverage mode: what counts as covered by itself, and which nodes the gaps are reported for';
        MODES.forEach(m => {
            const option = el('option', null, m.label);
            option.value = m.id;
            option.title = m.description;
            select.appendChild(option);
        });
        select.value = state.mode;
        select.onchange = () => send('setMode', { mode: select.value });
        bar.appendChild(select);
        const refresh = el('button', null, 'Refresh');
        refresh.onclick = () => send('refresh');
        bar.appendChild(refresh);
        root.appendChild(bar);

        // Validation
        const validation = state.validation;
        section('Validation');
        if (!validation) {
            row(root, null, null, 'Validating\u2026');
        } else if (validation.issues.length === 0) {
            row(root, 'OK', 'covered', 'The model is structurally valid', null,
                validation.patternsMatched + ' patterns matched, ' + validation.patternsSkipped + ' skipped, ' + validation.durationMs + ' ms');
        } else {
            validation.issues.forEach(i => {
                const sev = String(i.severity) === 'ERROR' ? 'error' : 'warning';
                row(root, sev, sev, String(i.message || ''), i.nodeIds || [],
                    String(i.code || '').toLowerCase().replace(/_/g, ' '));
            });
            const plural = (n, w) => n + ' ' + w + (n === 1 ? '' : 's');
            row(root, null, null, '', null, plural(validation.errors, 'error') + ', ' + plural(validation.warnings, 'warning') + ' \u00b7 '
                + validation.patternsMatched + ' patterns matched, ' + validation.durationMs + ' ms');
        }

        // Tree coverage: one statement per mode; the selected one lists its trees. A click selects the mode.
        const report = state.report;
        section('Tree coverage');
        if (!report) {
            row(root, null, null, 'Computing\u2026');
        } else {
            (report.modes || []).forEach(m => modeStatement(m, m.mode === state.mode ? report.roots : null));
        }

        // Gaps of the selected mode
        const gaps = state.gaps || [];
        const toggle = el('button', state.gapsActive ? 'on' : '', state.gapsActive ? 'Highlighted' : 'Highlight');
        toggle.title = 'Highlight uncovered nodes in the diagram';
        toggle.onclick = () => send('setGaps', { active: !state.gapsActive });
        section('Uncovered (' + gaps.length + ')', toggle);
        const roots = gaps.filter(g => g.rootCause);
        if (gaps.length === 0) {
            row(root, null, null, report ? 'Everything this mode asks about is covered' : 'Computing\u2026', null);
        }
        roots.forEach(g => row(root, g.coverage === 'PARTIAL' ? 'partial' : 'uncovered', g.coverage.toLowerCase(),
            g.label, [g.nodeId], g.message ? g.message : undefined));
        const affected = gaps.filter(g => !g.rootCause);
        if (affected.length > 0) {
            row(root, null, null, affected.length + ' more node(s) are uncovered only because of the ones above',
                affected.map(g => g.nodeId));
        }

        if (!report) return;
        section('Depth and breadth');
        Object.entries(report.trees).forEach(([kind, m]) => row(root, null, null,
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
        const line = el('div', 'row click mode' + (trees ? ' current' : ''));
        line.appendChild(el('span', 'badge ' + (cov ? cov.toLowerCase() : 'muted'), cov ? cov.toLowerCase() : 'empty'));
        const label = el('span', 'text');
        if (cov) {
            label.appendChild(document.createTextNode(subject + ' '));
            label.appendChild(el('b', null, VERB[cov]));
            label.appendChild(document.createTextNode(' ' + info.by));
        } else {
            label.appendChild(document.createTextNode((m.kind ? 'No ' + kindName(m.kind) + ' tree' : 'No coverable trees') + ' yet (' + info.label + ')'));
        }
        const counts = m.counts || {};
        const total = (counts.COVERED || 0) + (counts.PARTIAL || 0) + (counts.UNCOVERED || 0);
        if (total > 0) {
            label.appendChild(document.createElement('br'));
            label.appendChild(el('span', 'sub', (counts.COVERED || 0) + ' of ' + total + ' ' + (m.kind ? kindName(m.kind) : 'node')
                + ' nodes covered' + (counts.PARTIAL ? ', ' + counts.PARTIAL + ' partially' : '')));
        }
        line.appendChild(label);
        line.title = info.label + ': ' + info.description + (trees ? '' : ' (click to show in the diagram)');
        if (!trees) line.onclick = () => send('setMode', { mode: m.mode });
        root.appendChild(line);
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
