/**
 * Host-side mirror of the backend's {@code ModelVariant}.
 *
 * The {@link ModelVariantDescriptor#id} is the canonical discriminator
 * used everywhere:
 *   - Sent to the backend as {@code modelType} on {@code bigraph.create}
 *   - Persisted in the {@code .bigraph-meta} file as {@code modelType}
 *   - Matched by the corresponding server-side
 *     {@code IdeExtension.getSupportedModelVariants()} entry
 *
 * Each client-side extension declares its variants locally so that
 * adding a new diagram type doesn't require touching shared
 * orchestration code.
 */
export interface ModelVariantDescriptor {
    /** Stable id — must match the backend {@code ModelVariant.id}. */
    readonly id: string;
    /** Human-readable label shown in the quick-pick. */
    readonly label: string;
    /** Short description shown below the label in the quick-pick. */
    readonly description: string;
    /** VS Code Codicon id (without the {@code $(...)} wrapper). */
    readonly iconCodicon: string;
    /** File-name root used when suggesting a new file name. */
    readonly fileNameBase: string;
    /**
     * Whether this variant also produces a {@code .bigraph-meta} file
     * on the server when a new diagram is created. The frontend uses
     * this to know which artifacts to wait for before opening the file.
     */
    readonly writesMetaFile: boolean;
}

/**
 * Contract for an extension contributed to the VS Code extension host.
 *
 * <p>Host-side extensions are self-contained modules that declare what
 * diagram variants they introduce. They are mirrored on the webview
 * side ({@code webview/src/extension/}) and on the GLSP server
 * ({@code server/.../extensions/}), each handling the concerns of its
 * own runtime. Variant ids are the contract that ties the three sides
 * together.</p>
 */
export interface IClientExtension {
    /** Stable, machine-readable id (e.g. {@code "behavior-tree"}). */
    readonly id: string;
    /** Human-readable name. */
    readonly name: string;
    /**
     * Variants this extension contributes to the "new diagram" picker.
     * Returning an empty list is valid — extensions that only
     * customise editing of an existing variant don't have to declare
     * a new one.
     */
    readonly variants: ReadonlyArray<ModelVariantDescriptor>;
    /**
     * Optional hook for server {@code ResponseAction}s that need the
     * extension host (e.g. VS Code modal dialogs). Return {@code true}
     * when this extension consumed the action.
     */
    handleGlspServerAction?(context: GlspServerActionContext): boolean | Promise<boolean>;
    /**
     * Optional hook for messages posted from the GLSP webview to the
     * extension host (e.g. save-dialog flows triggered by palette export).
     * Return {@code true} when this extension consumed the message.
     */
    handleWebviewMessage?(context: WebviewMessageContext): boolean | Promise<boolean>;
}

/**
 * Context passed to {@link IClientExtension#handleWebviewMessage}.
 */
export interface WebviewMessageContext {
    readonly clientId: string;
    readonly filePath: string;
    readonly message: Record<string, unknown>;
}

/**
 * Context passed to {@link IClientExtension#handleGlspServerAction}.
 * Keeps GLSP bridge code free of extension-specific action shapes.
 */
export interface GlspServerActionContext {
    readonly clientId: string;
    readonly action: Record<string, unknown>;
    /** Send a follow-up action back to the GLSP server for this session. */
    sendAction(action: Record<string, unknown>): void;
}
