import { TriggerNodeCreationAction, TriggerEdgeCreationAction } from '@eclipse-glsp/client';
import { POPP_VARIANT_ID } from '../diagramExtension';
import { POPP_CONNECT_ELEMENT_TYPE_ID } from '../popp-actions';
import type { IVariantPalette, PaletteHostApi, VariantPaletteFactory } from '../../palette-types';
import variantHtml from './popp-variant.html';
import './popp-variant.css';

class PoppVariantPalette implements IVariantPalette {
    private container: HTMLElement | null = null;
    private activeCardEl: HTMLElement | null = null;

    constructor(private readonly host: PaletteHostApi) {}

    mount(container: HTMLElement): void {
        this.container = container;
        container.innerHTML = variantHtml;
        this.bindCardClicks();
        this.bindConnectTool();
    }

    refresh(): void { }

    dispose(): void {
        this.container = null;
        this.activeCardEl = null;
    }

    private bindCardClicks(): void {
        const root = this.container!;
        Array.from(root.querySelectorAll('.popp-card[data-popp-type]')).forEach(el => {
            const card = el as HTMLElement;
            const elementTypeId = card.dataset.poppType!;
            const nodeType = card.dataset.nodeType!;
            
            card.onclick = () => this.activateTool(card, () =>
                TriggerNodeCreationAction.create(elementTypeId, { args: { nodeType: nodeType } }));
        });
    }

    private bindConnectTool(): void {
        const root = this.container!;
        const card = root.querySelector<HTMLElement>('.popp-card[data-popp-connect]');
        if (!card) {
            return;
        }
        card.onclick = () => this.activateTool(card, () =>
            TriggerEdgeCreationAction.create(POPP_CONNECT_ELEMENT_TYPE_ID));
    }

    private activateTool(card: HTMLElement, createTrigger: () => TriggerNodeCreationAction | TriggerEdgeCreationAction): void {
        this.host.dispatcher.dispatch(createTrigger());
        this.activeCardEl?.classList.remove('bp-active');
        card.classList.add('bp-active');
        this.activeCardEl = card;
        this.host.notifyCreationToolActivated();
    }
}

export const createPoppPalette: VariantPaletteFactory =
    (host) => new PoppVariantPalette(host);

export { POPP_VARIANT_ID };