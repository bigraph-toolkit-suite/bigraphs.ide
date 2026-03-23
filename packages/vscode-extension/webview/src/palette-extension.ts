import { BigraphControlCreatorTool } from "./palette-tools/BigraphControlCreatorTool";

export interface PaletteButtonConfig {
    id: string;
    iconClass: string;
    tooltip: string;
    onClick: (event: MouseEvent) => void;
}

function dispatchAutoLayout(algorithm: string, bigraphStandard = false): void {
    window.dispatchEvent(new CustomEvent('bigraph-action', {
        detail: { kind: 'bigraph.autoLayout', algorithm, bigraphStandard }
    }));
}

export class BigraphPaletteExtension {
    private static readonly PANEL_ID = 'bigraph-custom-panel';

    constructor() {
        this.initialize();
    }

    private initialize(): void {
        const checkInterval = setInterval(() => {
            const palette = this.findPalette();
            if (palette) {
                if (!document.getElementById(BigraphPaletteExtension.PANEL_ID)) {
                    this.injectPanel(palette);
                }
                clearInterval(checkInterval);
            }
        }, 100);
    }

    private findPalette(): Element | null {
        return document.querySelector('.tool-palette') || 
               document.querySelector('.sprotty-palette') || 
               document.querySelector('.glsp-palette');
    }

    private injectPanel(palette: Element): void {
        // Outer wrapper: stacks rows vertically
        const panel = document.createElement('div');
        panel.id = BigraphPaletteExtension.PANEL_ID;
        Object.assign(panel.style, {
            backgroundColor: 'var(--vscode-sideBar-background)',
            padding: '8px',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'flex-start',
            borderRadius: '0 0 5px 5px',
            width: '100%',
            boxSizing: 'border-box',
            gap: '6px'
        });

        // Row 1: the + (Add Control) button
        const row1 = this.createRow(this.getControlButtons());

        // Row 2: all layout buttons
        const row2 = this.createRow(this.getLayoutButtons());

        panel.appendChild(row1);
        panel.appendChild(row2);
        palette.appendChild(panel);
        console.log("Bigraph: Custom palette panel injected.");
    }

    private createRow(buttons: PaletteButtonConfig[]): HTMLDivElement {
        const row = document.createElement('div');
        Object.assign(row.style, {
            display: 'flex',
            flexDirection: 'row',
            alignItems: 'center',
            gap: '6px',
            flexWrap: 'wrap'
        });
        buttons.forEach(config => row.appendChild(this.createButton(config)));
        return row;
    }

    private getControlButtons(): PaletteButtonConfig[] {
        return [
            {
                id: 'bigraph-add-control-btn',
                iconClass: 'codicon-plus',
                tooltip: 'Add Control',
                onClick: () => new BigraphControlCreatorTool().showAddControlDialog()
            }
        ];
    }

    private getLayoutButtons(): PaletteButtonConfig[] {
        return [
            {
                id: 'bigraph-layout-standard-btn',
                iconClass: 'codicon-graph',
                tooltip: 'Bigraph Standard Layout: outer names top, inner names bottom, nodes layered in between',
                onClick: () => dispatchAutoLayout('layered', true)
            },
            {
                id: 'bigraph-layout-layered-btn',
                iconClass: 'codicon-type-hierarchy-sub',
                tooltip: 'Auto Layout: Layered (hierarchical, top-down)',
                onClick: () => dispatchAutoLayout('layered')
            },
            {
                id: 'bigraph-layout-mrtree-btn',
                iconClass: 'codicon-list-tree',
                tooltip: 'Auto Layout: MrTree (tree-optimised)',
                onClick: () => dispatchAutoLayout('mrtree')
            },
            {
                id: 'bigraph-layout-force-btn',
                iconClass: 'codicon-activate-breakpoints',
                tooltip: 'Auto Layout: Force (spring/organic)',
                onClick: () => dispatchAutoLayout('force')
            },
            {
                id: 'bigraph-layout-stress-btn',
                iconClass: 'codicon-graph-scatter',
                tooltip: 'Auto Layout: Stress (compact organic)',
                onClick: () => dispatchAutoLayout('stress')
            }
        ];
    }

    private createButton(config: PaletteButtonConfig): HTMLButtonElement {
        const button = document.createElement('button');
        button.id = config.id;
        button.title = config.tooltip;
        Object.assign(button.style, {
            width: '30px',
            height: '30px',
            backgroundColor: 'var(--vscode-editor-background)',
            border: '1px solid var(--vscode-widget-border)',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            cursor: 'pointer',
            borderRadius: '3px',
            padding: '0'
        });
        button.innerHTML = `<span class="codicon ${config.iconClass}" style="color: white; font-size: 16px;"></span>`;
        button.onmouseover = () => button.style.backgroundColor = 'var(--vscode-list-hoverBackground)';
        button.onmouseout = () => button.style.backgroundColor = 'var(--vscode-editor-background)';
        button.onclick = config.onClick;
        return button;
    }


}
