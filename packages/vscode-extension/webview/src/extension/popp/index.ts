/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { TriggerNodeCreationAction } from '@eclipse-glsp/client';
import { appendPaletteExportSection } from '../palette-export';
import { getPaletteExportsForVariant } from '../extensions';
import type {
    IVariantPalette,
    PaletteHostApi,
    VariantPaletteFactory,
} from '../palette-types';
import { POPP_CONTROLS, PoppControlDescriptor, PoppDecompositionType } from './popp-controls';
import variantHtml from './popp-variant.html';
import '../core/palette/bigraph-variant.css';

const POPP_VARIANT_ID = 'popp';
const BIGRAPH_NODE_ELEMENT_TYPE = 'bigraph:node';

/**
 * POPP variant palette content — a single "POPP Elements" section
 * listing the node types a user can place for a Problem-Oriented
 * Project Planning diagram.
 *
 * <p>Unlike the core bigraph variant, the server contributes no
 * palette items for this variant (see
 * {@code BigraphToolPaletteItemProvider.isBigraphEditingPermitted}),
 * so the item list is built entirely from the static
 * {@link POPP_CONTROLS} descriptor here. Node creation still goes
 * through the generic {@code bigraph:node} creation flow — the POPP
 * model is a bigraph under the hood, so the existing
 * {@code CreateBigraphNodeOperationHandler} handles it without change.</p>
 */
class POPPVariantPalette implements IVariantPalette {
    private container: HTMLElement | null = null;
    private activeCreationEl: HTMLElement | null = null;

    constructor(private readonly host: PaletteHostApi) {}

    mount(container: HTMLElement): void {
        this.container = container;
        container.innerHTML = variantHtml;
        this.bindStaticEvents();
        this.refresh();
        appendPaletteExportSection(
            container,
            getPaletteExportsForVariant(POPP_VARIANT_ID, this.host),
            this.host
        );
    }

    refresh(): void {
        if (!this.container) return;
        const grid = this.container.querySelector<HTMLElement>('.bp-card-grid');
        if (!grid) return;
        grid.innerHTML = '';
        for (const control of POPP_CONTROLS) {
            grid.appendChild(this.buildCard(control));
        }
    }

    dispose(): void {
        this.container = null;
        this.activeCreationEl = null;
    }

    private bindStaticEvents(): void {
        const r = this.container!;
        const searchWrap = r.querySelector<HTMLElement>('.bp-search-wrap')!;
        const searchInput = r.querySelector<HTMLInputElement>('.bp-search-field')!;
        const searchClear = r.querySelector<HTMLElement>('.bp-search-clear')!;

        searchInput.oninput = () => {
            this.applyFilter(searchInput.value);
            searchWrap.classList.toggle('has-query', searchInput.value.length > 0);
        };
        searchInput.onkeydown = (ev) => {
            if (ev.key === 'Escape') {
                searchInput.value = '';
                this.applyFilter('');
                searchWrap.classList.remove('has-query');
            }
        };
        searchClear.onclick = () => {
            searchInput.value = '';
            this.applyFilter('');
            searchWrap.classList.remove('has-query');
            searchInput.focus();
        };
    }

    private buildCard(control: PoppControlDescriptor): HTMLElement {
        const card = document.createElement('div');
        card.className = 'bp-card';
        card.setAttribute('data-label', control.label);
        card.title = control.description;
        card.dataset.decomposition = 'NONE' satisfies PoppDecompositionType;
        card.innerHTML = `
            <div class="bp-card-name">${control.label}</div>
            ${control.supportsDecomposition ? `
            <div class="bp-segmented" data-decomp-picker>
                <div class="bp-seg bp-seg-active" data-decomp="NONE" title="No decomposition">–</div>
                <div class="bp-seg" data-decomp="AND" title="AND decomposition: all children required">AND</div>
                <div class="bp-seg" data-decomp="OR" title="OR decomposition: any child suffices">OR</div>
            </div>` : ''}
        `;

        if (control.supportsDecomposition) {
            const picker = card.querySelector<HTMLElement>('[data-decomp-picker]')!;
            picker.querySelectorAll<HTMLElement>('.bp-seg').forEach(opt => {
                opt.onclick = (e) => {
                    e.stopPropagation();
                    picker.querySelectorAll('.bp-seg').forEach(o => o.classList.remove('bp-seg-active'));
                    opt.classList.add('bp-seg-active');
                    card.dataset.decomposition = opt.dataset.decomp as PoppDecompositionType;
                };
            });
        }

        card.onclick = () => {
            const action = TriggerNodeCreationAction.create(BIGRAPH_NODE_ELEMENT_TYPE, {
                args: {
                    controlName: control.controlName,
                    arity: '0',
                    decompositionType: card.dataset.decomposition ?? 'NONE',
                },
            });
            this.host.dispatcher.dispatch(action);
            this.setActiveCreation(card);
        };
        return card;
    }

    private applyFilter(query: string): void {
        if (!this.container) return;
        const lc = query.toLowerCase();
        this.container.querySelectorAll<HTMLElement>('[data-label]').forEach(el => {
            const match = !lc || (el.getAttribute('data-label') ?? '').toLowerCase().includes(lc);
            el.classList.toggle('bp-filtered-out', !match);
        });
    }

    private setActiveCreation(el: HTMLElement): void {
        this.activeCreationEl?.classList.remove('bp-active');
        el.classList.add('bp-active');
        this.activeCreationEl = el;
        this.host.notifyCreationToolActivated();
    }
}

/**
 * Factory exported to {@code extensions.ts} as the POPP variant's
 * {@code createPalette} contribution.
 */
export const createPOPPPalette: VariantPaletteFactory = (host) => new POPPVariantPalette(host);
