/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * Modal dialog for the bigraph variant's "Add Control" action.
 *
 * <p>Moved here from {@code webview/src/palette/} as part of the
 * variant-aware palette refactor: it only makes sense for the bigraph
 * variant, so it lives alongside the rest of that variant's palette
 * code in {@code extension/core/palette/}.</p>
 */
export class BigraphControlCreatorTool {

    public showAddControlDialog() {
        const overlay = document.createElement('div');
        Object.assign(overlay.style, {
            position: 'fixed', top: '0', left: '0', width: '100%', height: '100%',
            backgroundColor: 'rgba(0,0,0,0.5)', zIndex: '10000',
            display: 'flex', justifyContent: 'center', alignItems: 'center'
        });

        const dialog = document.createElement('div');
        Object.assign(dialog.style, {
            backgroundColor: 'var(--vscode-editor-background)',
            color: 'var(--vscode-editor-foreground)',
            padding: '20px', borderRadius: '5px',
            border: '1px solid var(--vscode-widget-border)',
            boxShadow: '0 4px 8px rgba(0,0,0,0.5)',
            minWidth: '300px', display: 'flex', flexDirection: 'column', gap: '10px'
        });

        const title = document.createElement('h3');
        title.textContent = 'Add New Control';
        title.style.marginTop = '0';

        const nameInput = document.createElement('input');
        nameInput.placeholder = 'Control Name';
        Object.assign(nameInput.style, {
            padding: '5px', backgroundColor: 'var(--vscode-input-background)',
            color: 'var(--vscode-input-foreground)', border: '1px solid var(--vscode-input-border)'
        });

        const arityInput = document.createElement('input');
        arityInput.type = 'number';
        arityInput.placeholder = 'Arity (0-10)';
        arityInput.min = '0';
        Object.assign(arityInput.style, {
            padding: '5px', backgroundColor: 'var(--vscode-input-background)',
            color: 'var(--vscode-input-foreground)', border: '1px solid var(--vscode-input-border)'
        });

        const statusSelect = document.createElement('select');
        Object.assign(statusSelect.style, {
            padding: '5px', backgroundColor: 'var(--vscode-input-background)',
            color: 'var(--vscode-input-foreground)', border: '1px solid var(--vscode-input-border)'
        });

        const statuses = ['ATOMIC', 'ACTIVE', 'PASSIVE'];
        statuses.forEach(status => {
            const option = document.createElement('option');
            option.value = status;
            option.textContent = status.charAt(0) + status.slice(1).toLowerCase();
            statusSelect.appendChild(option);
        });

        const btnContainer = document.createElement('div');
        Object.assign(btnContainer.style, { display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' });

        const cancelBtn = document.createElement('button');
        cancelBtn.textContent = 'Cancel';
        Object.assign(cancelBtn.style, {
            padding: '5px 10px', cursor: 'pointer',
            backgroundColor: 'var(--vscode-button-secondaryBackground)',
            color: 'var(--vscode-button-secondaryForeground)', border: 'none'
        });
        cancelBtn.onclick = () => document.body.removeChild(overlay);

        const addBtn = document.createElement('button');
        addBtn.textContent = 'Add Control';
        Object.assign(addBtn.style, {
            padding: '5px 10px', cursor: 'pointer',
            backgroundColor: 'var(--vscode-button-background)',
            color: 'var(--vscode-button-foreground)', border: 'none'
        });
        addBtn.onclick = () => {
            const name = nameInput.value;
            const arity = parseInt(arityInput.value);
            const status = statusSelect.value;
            if (name && !isNaN(arity)) {
                this.dispatchCreateControlAction(name, arity, status);
                document.body.removeChild(overlay);
            }
        };

        btnContainer.appendChild(cancelBtn);
        btnContainer.appendChild(addBtn);
        dialog.appendChild(title);
        dialog.appendChild(nameInput);
        dialog.appendChild(arityInput);
        dialog.appendChild(statusSelect);
        dialog.appendChild(btnContainer);
        overlay.appendChild(dialog);
        document.body.appendChild(overlay);

        nameInput.focus();
    }

    private dispatchCreateControlAction(name: string, arity: number, status: string) {
        const event = new CustomEvent('bigraph-action', {
            detail: {
                kind: 'bigraph.createControl',
                name: name,
                arity: arity,
                status: status
            }
        });
        window.dispatchEvent(event);
        console.log("Bigraph: Dispatched createControl action", { name, arity, status });

        // Two refresh attempts: fast (1s) for a responsive server, slow
        // (3s) as a fallback. The host re-fetches palette items both times.
        setTimeout(() => window.dispatchEvent(new CustomEvent('bigraph-palette-refresh')), 1000);
        setTimeout(() => window.dispatchEvent(new CustomEvent('bigraph-palette-refresh')), 3000);
    }
}
