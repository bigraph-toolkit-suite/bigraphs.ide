import type { IDiagramExtension } from '../extensions';
import { poppDiagramModule } from './popp-diagram-module';
import { createPoppPalette } from './palette';
import { RequestJsonExportAction } from './popp-actions';
import { acquireVsCodeApi } from '../palette-export';
import {
    POPP_CONTEXT_MENU_ELEMENT_TYPES,
    getPoppContextMenuItems,
} from './context-menu/popp-context-menu-items';

export const POPP_VARIANT_ID = 'popp';

export const poppDiagramExtension: IDiagramExtension = {
    id: 'popp',
    name: 'Problem-oriented Project Planning',
    variantId: POPP_VARIANT_ID,
    iconCodicon: 'tasklist',
    module: poppDiagramModule,
    createPalette: createPoppPalette,
    contextMenuElementTypes: POPP_CONTEXT_MENU_ELEMENT_TYPES,
    getContextMenuItems: getPoppContextMenuItems,
    getPaletteExports: () => [{
        id: 'popp-json',
        label: 'JSON',
        title: 'Export the POPP model as JSON',
        run: host => { void host.dispatcher.dispatch(RequestJsonExportAction.create()); }
    }],
    getPaletteImports: () => [{
        id: 'popp-json',
        label: 'JSON',
        title: 'Replace this POPP model with one from a JSON file',
        run: () => acquireVsCodeApi()?.postMessage({ type: 'popp.importJsonRequest' })
    }],
};
