import { injectable, inject } from 'inversify';
import { IContextMenuService, MenuItem, Anchor, TYPES, IActionDispatcher } from '@eclipse-glsp/client';

@injectable()
export class BigraphContextMenuService implements IContextMenuService {

    @inject(TYPES.IActionDispatcher) protected actionDispatcher!: IActionDispatcher;

    private currentMenu: HTMLElement | undefined;

    show(items: MenuItem[], anchor: Anchor, onHide?: () => void): void {
        console.log('📱 [BigraphContextMenuService] show called with', items.length, 'items:', items.map(i => i.label).join(', '));
        this.hide();

        const menu = document.createElement('div');
        menu.className = 'sprotty-context-menu';
        
        // Position the menu
        const x = (anchor as any).x !== undefined ? (anchor as any).x : (anchor as any).clientX;
        const y = (anchor as any).y !== undefined ? (anchor as any).y : (anchor as any).clientY;
        
        menu.style.left = `${x}px`;
        menu.style.top = `${y}px`;
        menu.style.display = 'block';
        menu.style.position = 'absolute';
        menu.style.zIndex = '1000000'; // Ensure it's on top

        items.forEach(item => {
            const itemElement = document.createElement('div');
            itemElement.className = 'sprotty-context-menu-item';
            if (item.isEnabled === undefined || item.isEnabled()) {
                itemElement.innerText = item.label;
                itemElement.onclick = (e) => {
                    e.stopPropagation();
                    console.log('✅ Menu item clicked:', item.label);
                    if (item.actions) {
                        item.actions.forEach(action => {
                            // Handle local rename action - show inline input
                            if (action.kind === 'localRename') {
                                const elementId = (action as any).elementId;
                                this.hide();
                                this.showRenameInput(elementId, anchor);
                            } else {
                                this.actionDispatcher.dispatch(action);
                            }
                        });
                    }
                    if (item.actions?.[0]?.kind !== 'localRename') {
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

        // Hide on click outside
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

    private showRenameInput(elementId: string, anchor: Anchor): void {
        // Create input overlay
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:1000001;';
        
        const inputBox = document.createElement('div');
        inputBox.style.cssText = `
            position:absolute;
            left:${(anchor as any).x || (anchor as any).clientX}px;
            top:${(anchor as any).y || (anchor as any).clientY}px;
            background:var(--vscode-input-background);
            border:1px solid var(--vscode-input-border);
            padding:4px;
            border-radius:3px;
            box-shadow:0 2px 8px rgba(0,0,0,0.3);
        `;
        
        const input = document.createElement('input');
        input.type = 'text';
        input.placeholder = 'Enter new name...';
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
        
        const cleanup = () => {
            overlay.remove();
        };
        
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && input.value.trim()) {
                this.actionDispatcher.dispatch({
                    kind: 'bigraphRenameNode',
                    elementId: elementId,
                    newName: input.value.trim()
                } as any);
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
