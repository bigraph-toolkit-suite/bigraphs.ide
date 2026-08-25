import { injectable, inject } from 'inversify';
import { IContextMenuService, MenuItem, Anchor, TYPES, IActionDispatcher, Action } from '@eclipse-glsp/client';

import { LocalRenameAction } from './extension/context-menu/local-rename-action';
import { getExtensions } from './extension/extensions';

@injectable()
export class BigraphContextMenuService implements IContextMenuService {

    @inject(TYPES.IActionDispatcher) protected actionDispatcher!: IActionDispatcher;

    private currentMenu: HTMLElement | undefined;

    /**
     * Client-local context-menu actions (no server round-trip) are
     * delegated to the registered diagram extensions. Action kinds are
     * namespaced per extension (e.g. {@code localBtSetActionInputs}),
     * and the menu items themselves are already variant-gated by
     * {@code ExtensionContextMenuProvider}, so first-consumer-wins is
     * safe here.
     */
    protected handleLocalAction(action: Action, anchor: Anchor): boolean {
        for (const extension of getExtensions()) {
            if (extension.handleLocalContextMenuAction?.(action, anchor, this.actionDispatcher)) {
                return true;
            }
        }
        return false;
    }

    show(items: MenuItem[], anchor: Anchor, onHide?: () => void): void {
        this.hide();

        const menu = document.createElement('div');
        menu.className = 'sprotty-context-menu';

        const x = (anchor as { x?: number; clientX?: number }).x
            ?? (anchor as { clientX?: number }).clientX
            ?? 0;
        const y = (anchor as { y?: number; clientY?: number }).y
            ?? (anchor as { clientY?: number }).clientY
            ?? 0;

        menu.style.left = `${x}px`;
        menu.style.top = `${y}px`;
        menu.style.display = 'block';
        menu.style.position = 'absolute';
        menu.style.zIndex = '1000000';

        items.forEach(item => {
            const itemElement = document.createElement('div');
            itemElement.className = 'sprotty-context-menu-item';
            if (item.isEnabled === undefined || item.isEnabled()) {
                itemElement.innerText = item.label;
                itemElement.onclick = (e) => {
                    e.stopPropagation();
                    let keepMenuOpen = false;
                    if (item.actions) {
                        item.actions.forEach(action => {
                            if (LocalRenameAction.is(action)) {
                                this.hide();
                                this.showRenameInput(action, anchor);
                                keepMenuOpen = true;
                            } else if (this.handleLocalAction(action, anchor)) {
                                this.hide();
                                keepMenuOpen = true;
                            } else {
                                this.actionDispatcher.dispatch(action);
                            }
                        });
                    }
                    if (!keepMenuOpen) {
                        this.hide();
                    }
                    if (onHide) onHide();
                };
            } else {
                itemElement.innerText = item.label;
                itemElement.classList.add('disabled');
            }
            menu.appendChild(itemElement);
        });

        document.body.appendChild(menu);
        this.currentMenu = menu;

        const hideMenu = (e: MouseEvent) => {
            if (!menu.contains(e.target as Node)) {
                this.hide();
                if (onHide) onHide();
                document.removeEventListener('click', hideMenu);
            }
        };
        setTimeout(() => document.addEventListener('click', hideMenu), 10);
    }

    protected hide(): void {
        if (this.currentMenu) {
            this.currentMenu.remove();
            this.currentMenu = undefined;
        }
    }

    private showRenameInput(action: LocalRenameAction, anchor: Anchor): void {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:1000001;';

        const anchorX = (anchor as { x?: number; clientX?: number }).x
            ?? (anchor as { clientX?: number }).clientX
            ?? 0;
        const anchorY = (anchor as { y?: number; clientY?: number }).y
            ?? (anchor as { clientY?: number }).clientY
            ?? 0;

        const inputBox = document.createElement('div');
        inputBox.style.cssText = `
            position:absolute;
            left:${anchorX}px;
            top:${anchorY}px;
            background:var(--vscode-input-background);
            border:1px solid var(--vscode-input-border);
            padding:4px;
            border-radius:3px;
            box-shadow:0 2px 8px rgba(0,0,0,0.3);
        `;

        const input = document.createElement('input');
        input.type = 'text';
        input.placeholder = 'Enter new name...';
        input.value = action.initialName ?? '';
        input.style.cssText = `
            width:200px;
            padding:4px 8px;
            background:var(--vscode-input-background);
            color:var(--vscode-input-foreground);
            border:none;
            outline:none;
            font-size:13px;
        `;

        inputBox.appendChild(input);
        overlay.appendChild(inputBox);
        document.body.appendChild(overlay);

        input.focus();
        if (input.value) {
            input.select();
        }

        const cleanup = () => {
            overlay.remove();
        };

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && input.value.trim()) {
                this.actionDispatcher.dispatch({
                    kind: action.submitActionKind,
                    newName: input.value.trim(),
                    ...action.submitPayload,
                } as Action);
                cleanup();
            } else if (e.key === 'Escape') {
                cleanup();
            }
        });

        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                cleanup();
            }
        });
    }
}
