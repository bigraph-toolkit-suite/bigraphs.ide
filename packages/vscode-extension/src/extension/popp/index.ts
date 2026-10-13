import { IClientExtension, ModelVariantDescriptor, WebviewMessageContext } from '../types';
import { PoppSidebarState, getPoppCoverageViewProvider, revealPoppSidebar } from './coverageView';

export const POPP_VARIANT_ID = 'popp';

const POPP_VARIANT: ModelVariantDescriptor = {
    id: POPP_VARIANT_ID,
    label: 'Problem-Oriented Project Planning',
    description: 'Problem-Oriented Project Planning encoded as a bigraph',
    iconCodicon: 'tasklist',
    fileNameBase: 'new-popp-model',
    writesMetaFile: true,
};

export const problemOrientedProjectPlanningExtension: IClientExtension = {
    id: 'popp',
    name: 'Problem-Oriented Project Planning',
    variants: [POPP_VARIANT],

    /** Relays between the diagram webview and the POPP sidebar view. */
    handleWebviewMessage(context: WebviewMessageContext): boolean {
        const { message, clientId } = context;
        if (message.type === 'popp.sidebarState') {
            getPoppCoverageViewProvider()?.setState(clientId, message.state as PoppSidebarState);
            return true;
        }
        if (message.type === 'popp.openSidebar') {
            void revealPoppSidebar();
            return true;
        }
        return false;
    },
};
