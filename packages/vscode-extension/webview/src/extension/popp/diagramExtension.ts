import type { IDiagramExtension } from '../extensions';
import { poppDiagramModule } from './popp-diagram-module';
import { createPoppPalette } from './palette';
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
};
