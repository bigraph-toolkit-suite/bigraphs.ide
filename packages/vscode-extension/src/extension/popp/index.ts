import { IClientExtension, ModelVariantDescriptor } from '../types';

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
};
