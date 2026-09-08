import { coreClientExtension } from './core';
import { problemOrientedProjectPlanningExtension } from './popp';
import { IClientExtension, ModelVariantDescriptor, GlspServerActionContext, WebviewMessageContext } from './types';

/**
 * Host-side registry of all client extensions.
 *
 * <p>Add a new extension by appending it to {@link CLIENT_EXTENSIONS};
 * everything that consumes variants ({@code create-bigraph.ts}, future
 * meta loaders, …) flows through {@link getClientExtensions} /
 * {@link getModelVariants} and stays oblivious to which extension
 * contributed what.</p>
 *
 * <p>Ordering matters only for UI presentation — variants are picked
 * up in the order their owning extensions were registered. Core comes
 * first so the classic bigraph appears at the top of the picker.</p>
 */
const CLIENT_EXTENSIONS: ReadonlyArray<IClientExtension> = Object.freeze([
    coreClientExtension,
    problemOrientedProjectPlanningExtension,
]);

/** All registered client extensions, in registration order. */
export function getClientExtensions(): ReadonlyArray<IClientExtension> {
    return CLIENT_EXTENSIONS;
}

/**
 * Flattened list of every variant contributed by every registered
 * extension, ready to be fed into a quick-pick.
 */
export function getModelVariants(): ReadonlyArray<ModelVariantDescriptor> {
    return CLIENT_EXTENSIONS.flatMap(ext => ext.variants);
}

/** Look up a variant by its canonical id. */
export function findModelVariant(id: string): ModelVariantDescriptor | undefined {
    return getModelVariants().find(v => v.id === id);
}

/**
 * Delegate a GLSP server action to registered client extensions.
 * Returns {@code true} when an extension consumed the action.
 */
export async function dispatchGlspServerAction(
    clientId: string,
    action: Record<string, unknown>,
    sendAction: (action: Record<string, unknown>) => void
): Promise<boolean> {
    for (const ext of CLIENT_EXTENSIONS) {
        if (!ext.handleGlspServerAction) {
            continue;
        }
        const handled = await ext.handleGlspServerAction({ clientId, action, sendAction });
        if (handled) {
            return true;
        }
    }
    return false;
}

/**
 * Delegate a webview postMessage to registered client extensions.
 * Returns {@code true} when an extension consumed the message.
 */
export async function dispatchWebviewMessage(
    clientId: string,
    filePath: string,
    message: Record<string, unknown>
): Promise<boolean> {
    for (const ext of CLIENT_EXTENSIONS) {
        if (!ext.handleWebviewMessage) {
            continue;
        }
        const handled = await ext.handleWebviewMessage({ clientId, filePath, message });
        if (handled) {
            return true;
        }
    }
    return false;
}

export { IClientExtension, ModelVariantDescriptor, GlspServerActionContext, WebviewMessageContext };
