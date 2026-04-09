// @ts-nocheck
declare function acquireVsCodeApi(): { postMessage: (message: unknown) => void };

(function() {
    const vscode = acquireVsCodeApi();
    const state = {
        context: null,
        leftPath: '',
        rightPath: '',
    };

    const emptyStateEl = document.getElementById('empty-state');
    const editorEl = document.getElementById('editor');
    const modeTitleEl = document.getElementById('mode-title');
    const ruleNameInput = document.getElementById('rule-name');
    const leftSlot = document.getElementById('left-slot');
    const rightSlot = document.getElementById('right-slot');
    const saveBtn = document.getElementById('save-btn');

    function dndDebug(event, details) {
        try {
            console.log('[RR-DnD]', event, details || {});
            vscode.postMessage({
                type: 'dndDebugLog',
                source: 'rewriteRuleEditor.slots',
                event: event,
                details: details || {}
            });
        } catch (_) {}
    }

    class DragServiceClient {
        constructor(vscodeApi) {
            this.vscode = vscodeApi;
            this.sessionActive = false;
            this.payloadType = null;
            this.hoveredZoneId = null;
            this.dropZones = new Map();
        }

        registerDragZone(element, payloadFactory, typeOfPayload) {
            if (!element) return;
            element.addEventListener('mousedown', () => {
                try {
                    const payload = payloadFactory ? payloadFactory() : null;
                    dndDebug('registerDragZone-mousedown', { typeOfPayload: typeOfPayload, hasPayload: !!payload });
                } catch (_) {}
            });
        }

        registerDropZonePayload(element, payloadTypeToReceive, callback, zoneId) {
            if (!element || !zoneId) return;
            this.dropZones.set(zoneId, {
                element: element,
                payloadType: payloadTypeToReceive,
                callback: callback
            });
            this.vscode.postMessage({
                type: 'dndRegisterZone',
                zoneId: zoneId,
                accepts: [payloadTypeToReceive],
                priority: 10
            });
            const setHover = (active) => {
                if (!this.sessionActive) return;
                const next = active ? zoneId : null;
                if (this.hoveredZoneId === next) return;
                this.hoveredZoneId = next;
                this.vscode.postMessage({ type: 'dndHoverZone', zoneId: next });
                dndDebug('hover-change', { zoneId: next });
            };
            element.addEventListener('mouseenter', () => setHover(true));
            element.addEventListener('mouseleave', () => setHover(false));
            element.addEventListener('dragenter', (e) => { e.preventDefault(); setHover(true); });
            element.addEventListener('dragover', (e) => { e.preventDefault(); setHover(true); });
            element.addEventListener('dragleave', (e) => {
                const rel = e.relatedTarget;
                if (!rel || !element.contains(rel)) setHover(false);
            });
        }

        init() {
            document.addEventListener('dragover', (e) => {
                if (!this.sessionActive) return;
                this.updateHoverFromTarget(e.target);
            }, true);
            document.addEventListener('drop', (e) => {
                if (!this.sessionActive) return;
                this.updateHoverFromTarget(e.target);
                this.finalize('drop');
            }, true);
            document.addEventListener('mouseup', () => {
                if (!this.sessionActive) return;
                this.finalize('mouseup');
            }, true);
            window.addEventListener('message', (event) => this.handleMessage(event.data || {}));
        }

        updateHoverFromTarget(target) {
            let hovered = null;
            for (const [zoneId, zone] of this.dropZones.entries()) {
                if (target && zone.element.contains(target)) {
                    hovered = zoneId;
                    break;
                }
            }
            if (this.hoveredZoneId !== hovered) {
                this.hoveredZoneId = hovered;
                this.vscode.postMessage({ type: 'dndHoverZone', zoneId: hovered });
                dndDebug('document-hover-change', { zoneId: hovered });
            }
        }

        finalize(reason) {
            if (!this.sessionActive) return;
            if (!this.hoveredZoneId) {
                dndDebug('finalize-no-hover', { reason: reason });
                this.resetSession();
                return;
            }
            dndDebug('finalize-request', { reason: reason, zoneId: this.hoveredZoneId });
            this.vscode.postMessage({ type: 'dndFinalize', reason: reason });
        }

        handleMessage(msg) {
            if (msg.type === 'dndSessionStarted') {
                this.sessionActive = true;
                this.payloadType = msg.payloadType || null;
                this.hoveredZoneId = null;
                this.vscode.postMessage({ type: 'dndHoverZone', zoneId: null });
                dndDebug('dndSessionStarted', { payloadType: this.payloadType });
                return true;
            }
            if (msg.type === 'dndSessionEnded') {
                dndDebug('dndSessionEnded', { reason: msg.reason });
                this.resetSession();
                return true;
            }
            if (msg.type === 'dndDropDelivered') {
                const zone = this.dropZones.get(msg.zoneId);
                if (!zone) return true;
                if (zone.payloadType !== msg.payloadType) return true;
                zone.callback(msg.payload);
                dndDebug('dndDropDelivered', { zoneId: msg.zoneId, payloadType: msg.payloadType });
                this.resetSession();
                return true;
            }
            return false;
        }

        resetSession() {
            this.sessionActive = false;
            this.payloadType = null;
            this.hoveredZoneId = null;
            this.vscode.postMessage({ type: 'dndHoverZone', zoneId: null });
        }
    }

    function getBaseName(filePath) {
        if (!filePath) return '';
        const normalized = filePath.replace(/\\\\/g, '/');
        const parts = normalized.split('/');
        const fileName = parts[parts.length - 1] || normalized;
        const lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.slice(0, lastDotIndex);
        }
        return fileName;
    }

    function renderSlot(slotEl, side, path) {
        if (!slotEl) return;
        const valueEl = slotEl.querySelector('.slot-value');
        if (!valueEl) return;
        const hasPath = !!path;
        slotEl.classList.toggle('empty', !hasPath);
        slotEl.classList.toggle('filled', hasPath);
        valueEl.textContent = hasPath ? getBaseName(path) : 'Drop bigraph here';
        slotEl.title = hasPath ? path : '';
        slotEl.dataset.side = side;
    }

    function renderEditor() {
        const hasContext = !!state.context;
        emptyStateEl.classList.toggle('hidden', hasContext);
        editorEl.classList.toggle('hidden', !hasContext);
        if (!hasContext) {
            saveBtn.disabled = true;
            return;
        }
        modeTitleEl.textContent = state.context.mode === 'edit' ? 'Edit Rule' : 'New Rule';
        ruleNameInput.value = state.context.label || '';
        state.leftPath = state.context.leftPath || '';
        state.rightPath = state.context.rightPath || '';
        renderSlot(leftSlot, 'left', state.leftPath);
        renderSlot(rightSlot, 'right', state.rightPath);
        updateSaveButtonState();
    }

    function updateSaveButtonState() {
        const hasLabel = ruleNameInput.value.trim().length > 0;
        saveBtn.disabled = !(state.context && hasLabel && state.leftPath && state.rightPath);
    }

    function applyDroppedFileToSide(side, payload) {
        const files = Array.isArray(payload) ? payload : [];
        const file = files[0];
        if (!file || !file.fsPath) return;
        const relativePath = file.relativePath || file.fsPath.replace(/\\\\/g, '/').split('/').slice(-1)[0];
        if (side === 'left') {
            state.leftPath = relativePath;
            renderSlot(leftSlot, 'left', state.leftPath);
        } else {
            state.rightPath = relativePath;
            renderSlot(rightSlot, 'right', state.rightPath);
        }
        updateSaveButtonState();
    }

    const dndClient = new DragServiceClient(vscode);
    dndClient.init();
    dndClient.registerDropZonePayload(leftSlot, 'bigraphFiles', (payload) => applyDroppedFileToSide('left', payload), 'ruleEditor.left');
    dndClient.registerDropZonePayload(rightSlot, 'bigraphFiles', (payload) => applyDroppedFileToSide('right', payload), 'ruleEditor.right');

    leftSlot.addEventListener('click', function() {
        if (!state.leftPath) return;
        vscode.postMessage({ type: 'openRuleSide', payload: { path: state.leftPath } });
    });
    rightSlot.addEventListener('click', function() {
        if (!state.rightPath) return;
        vscode.postMessage({ type: 'openRuleSide', payload: { path: state.rightPath } });
    });

    ruleNameInput.addEventListener('input', updateSaveButtonState);
    saveBtn.addEventListener('click', function() {
        if (!state.context) return;
        const label = ruleNameInput.value.trim();
        if (!label || !state.leftPath || !state.rightPath) return;
        vscode.postMessage({
            type: 'saveRewriteRule',
            payload: {
                mode: state.context.mode,
                setLabel: state.context.setLabel,
                setIndex: state.context.setIndex,
                originalLabel: state.context.originalLabel,
                label: label,
                leftPath: state.leftPath,
                rightPath: state.rightPath
            }
        });
    });

    window.addEventListener('message', function(event) {
        const msg = event.data || {};
        if (dndClient.handleMessage(msg)) return;
        if (msg.type === 'showRuleEditor' && msg.context) {
            state.context = msg.context;
            renderEditor();
        }
    });

    vscode.postMessage({ type: 'ready' });
})();
