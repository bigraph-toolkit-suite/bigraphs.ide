import { IClientExtension, ModelVariantDescriptor } from '../types';

/**
 * Canonical id of the classic bigraph variant. Must match the server-side
 * {@code CoreIdeExtension.BIGRAPH_VARIANT_ID}.
 */
export const BIGRAPH_VARIANT_ID = 'bigraph';

const BIGRAPH_VARIANT: ModelVariantDescriptor = {
    id: BIGRAPH_VARIANT_ID,
    label: 'Bigraph',
    description: 'Classic bigraph diagram (place graph + link graph)',
    iconCodicon: 'symbol-structure',
    fileNameBase: 'new-bigraph',
    // Plain bigraphs intentionally don't ship a meta file at creation
    // time — kept this way so legacy files stay byte-identical.
    writesMetaFile: false,
};

/** Built-in "core" client extension that declares the {@code bigraph} variant. */
export const coreClientExtension: IClientExtension = {
    id: 'core',
    name: 'Core Bigraph',
    variants: [BIGRAPH_VARIANT],
};
