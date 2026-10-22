import type * as vscode from 'vscode';

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
 * The diagram a side panel is following.
 */
export interface ExtensionSidePanelDiagram {
    /** Absolute path of the focused {@code .xmi} diagram. */
    readonly fsPath: string;
    /**
     * Variant id from the root {@code modelType} attribute of the sibling
     * {@code .bigraph-meta} file. Diagrams with no meta file yet use the
     * classic bigraph id until that file is written.
     */
    readonly modelType: string;
}

/**
 * The document surface for one contributed side panel.
 *
 * <p>Assign {@link html} to any markup. Inline scripts run in this
 * panel and can call {@code acquireVsCodeApi()} the same way a normal
 * VS Code webview does. Messages posted from that script are delivered
 * to {@link onDidReceiveMessage}. {@link diagram} is already set when
 * {@link ExtensionSidePanel.resolve} runs. {@link onDidChangeDiagram}
 * fires when the user switches to another diagram that keeps this same
 * panel open. Return a disposable from {@code resolve} to tear those
 * listeners down.</p>
 */
export interface ExtensionSidePanelView {
    html: string;
    /** Id of the extension that registered this panel. */
    readonly extensionId: string;
    /** Root of this VS Code extension, for local resources. */
    readonly extensionUri: vscode.Uri;
    /** Focused diagram. Updated in place when the panel stays open. */
    readonly diagram: ExtensionSidePanelDiagram;
    asWebviewUri(localResource: vscode.Uri): vscode.Uri;
    postMessage(message: unknown): Thenable<boolean>;
    onDidReceiveMessage(listener: (message: unknown) => void): vscode.Disposable;
    onDidChangeDiagram(listener: (diagram: ExtensionSidePanelDiagram) => void): vscode.Disposable;
}

/**
 * The one left-sidebar panel an extension may show.
 *
 * <p>Set it on {@link IClientExtension.sidePanel}. The host shows it in
 * the Bigraph Explorer sidebar while a diagram of one of this extension's
 * variants is the focused editor, and hides it for any other focused
 * editor. Switching diagrams leaves the activity bar on its current
 * item. Only one side panel is visible at a time.</p>
 *
 * <pre>
 * sidePanel: {
 *     id: 'my-tools',
 *     htmlFile: 'extension-sidebar/my-tools.html'
 * }
 * </pre>
 */
export interface ExtensionSidePanel {
    /** Stable id, unique across every registered side panel. */
    readonly id: string;
    /**
     * HTML file for this panel's layout, and for its scripts if you
     * put them in that file or next to it. Path is relative to the
     * VS Code extension root (the folder that contains {@code package.json}),
     * with forward slashes. The host loads it when the panel opens.
     * Relative {@code src} and {@code href} values are turned into
     * webview URIs, so a sibling {@code .js} or {@code .css} file works.
     * The sidebar title is always {@link IClientExtension.name}.
     */
    readonly htmlFile?: string;
    /**
     * Optional host-side hook. Runs after {@link htmlFile} has been read
     * into {@link ExtensionSidePanelView.html}, and before that document
     * is shown in the webview. {@link ExtensionSidePanelView.diagram} is
     * already set. Use the hook for messages that need the extension host.
     * Have the page post a ready message once it has loaded, because a
     * message posted during {@code resolve} can arrive before the document
     * exists. Return a disposable to tear the hook down when the view is
     * disposed.
     */
    resolve?(
        panel: ExtensionSidePanelView
    ): void | vscode.Disposable | Thenable<void | vscode.Disposable | undefined>;
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
     * Optional left-sidebar panel. Shown in the Bigraph Explorer sidebar
     * while a diagram of one of {@link variants} is the focused editor.
     * See {@link ExtensionSidePanel}.
     */
    readonly sidePanel?: ExtensionSidePanel;
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
