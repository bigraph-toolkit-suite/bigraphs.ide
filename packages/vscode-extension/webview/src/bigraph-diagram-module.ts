/** @jsx svg */
import {
    initializeDiagramContainer,
    ContainerConfiguration,
    TYPES,
    ActionDispatcher,
    RequestModelAction,
    SetModelAction,
    UpdateModelAction,
    configureDefaultModelElements,
    configureModelElement,
    ShapeView,
    PolylineEdgeView,
    RenderingContext,
    IViewArgs,
    GNode,
    GEdge,
    ArgsAware,
    Args,
    setAttr,
    contextMenuModule,
    IActionHandler,
    Action,
    configureActionHandler,
} from '@eclipse-glsp/client';
import { RequestRenameNodeAction } from './rename-action';
import { Container, ContainerModule, injectable } from 'inversify';
import { VNode } from 'snabbdom';
import { svg } from '@eclipse-glsp/client';
import { BigraphContextMenuProvider } from './context-menu';
import { BigraphContextMenuService } from './bigraph-context-menu-service';
import { BigraphPlaceEdgeView } from './bigraph-place-edge-view';
import { setClass } from 'sprotty';

/** Class for diagram labels; fill is set in CSS using body.vscode-light / vscode-dark (see bigraph-styles.css). */
const DIAGRAM_LABEL_CLASS = 'bigraph-svg-label';

function labelClass(text: VNode): void {
    setClass(text, DIAGRAM_LABEL_CLASS, true);
}

/**
 * Custom view for hyper edge nodes (green text, no frame)
 */
@injectable()
export class BigraphHyperEdgeView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        //console.log('🔗 Rendering hyper edge node:', node.id, 'with args:', node.args);

        // Get label from node arguments
        const label = typeof node.args?.label === 'string' ? node.args.label : 'HyperEdge';

        // Create subtle background for visibility (very light green)
        const background = svg('rect', {
            x: '0',
            y: '0',
            width: Math.max(node.size.width, 0).toString(),
            height: Math.max(node.size.height, 0).toString(),
            fill: '#E8F5E8', // Very light green background
            stroke: '#4CAF50', // Green border
            'stroke-width': '1',
            'stroke-dasharray': '3,3', // Dashed border to distinguish from normal nodes
            rx: '4', // Rounded corners
            ry: '4'
        });

        // Create text element
        const text = svg('text', {
            x: (node.size.width / 2).toString(),
            y: (node.size.height / 2).toString(),
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': '11',
            'font-weight': 'bold'
        }, label);
        labelClass(text);

        // Render children
        const children = context.renderChildren(node);
        
        // Main container group - background + text
        const vnode = svg('g', {});
        vnode.children = [background, text, ...children];

        // Apply CSS classes from server
        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => {
                setAttr(vnode, 'class', cssClass);
            });
        }

        //console.log('✅ Rendered hyper edge node:', label);
        return vnode;
    }
}

/**
 * Custom view for Inner Names (input interface)
 * Shape: V-notch opening UPWARD at TOP - represents "receiving" or "docking"
 *   ┌───╲  ╱───┐
 *   │    ╲╱    │  ← V-notch opens upward (receiving/accepting)
 *   │   name   │
 *   └──────────┘
 */
@injectable()
export class BigraphInnerNameView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        //console.log('📍 Rendering inner name:', node.id, 'with args:', node.args);

        const label = typeof node.args?.label === 'string' ? node.args.label : 'InnerName';
        const width = Math.max(node.size.width, 0);
        const height = Math.max(node.size.height, 0);
        const notchDepth = 10;  // How deep the V-notch goes
        const r = 4;            // Corner radius for the 4 rectangular corners
        const apexR = 3;        // Radius for the V-notch apex

        // The 5 vertices of the pentagon (NO flat top — V spans full width):
        //   P1=(0,0)  →  P2=(w/2, notchDepth)  →  P3=(w,0)  →  P4=(w,h)  →  P5=(0,h)
        //
        // Unit vector along the left diagonal P1→P2: direction (w/2, notchDepth)
        const diagLen = Math.sqrt((width / 2) * (width / 2) + notchDepth * notchDepth);
        const dux = (width / 2) / diagLen;  // unit x along diagonal
        const duy = notchDepth / diagLen;    // unit y along diagonal

        // --- Corner 1: Top-left (0,0) — where left-side meets left-diagonal ---
        // Incoming from P5→P1 along left side: direction (0,-1). Stop r before P1: (0, r)
        // Outgoing P1→P2 along diagonal: direction (dux, duy). Start r after P1: (r*dux, r*duy)
        const tl_in_x = 0, tl_in_y = r;
        const tl_out_x = r * dux, tl_out_y = r * duy;

        // --- Corner 2: V-apex (w/2, notchDepth) — where left-diagonal meets right-diagonal ---
        // Incoming P1→P2: stop apexR before P2 along diagonal
        const apex_in_x = width / 2 - apexR * dux;
        const apex_in_y = notchDepth - apexR * duy;
        // Outgoing P2→P3: direction (w/2, -notchDepth), unit (dux, -duy). Start apexR after P2
        const apex_out_x = width / 2 + apexR * dux;
        const apex_out_y = notchDepth - apexR * duy;

        // --- Corner 3: Top-right (w, 0) — where right-diagonal meets right-side ---
        // Incoming P2→P3: direction (dux, -duy). Stop r before P3
        const tr_in_x = width - r * dux;
        const tr_in_y = r * duy;  // 0 - r*(-duy) = r*duy
        // Outgoing P3→P4 along right side: direction (0,1). Start r after P3: (w, r)
        const tr_out_x = width, tr_out_y = r;

        // --- Corner 4: Bottom-right (w, h) — standard 90° corner ---
        const br_in_x = width, br_in_y = height - r;
        const br_out_x = width - r, br_out_y = height;

        // --- Corner 5: Bottom-left (0, h) — standard 90° corner ---
        const bl_in_x = r, bl_in_y = height;
        const bl_out_x = 0, bl_out_y = height - r;

        // Build path clockwise: TL → apex → TR → BR → BL → back to TL
        const pathData = [
            `M ${tl_out_x},${tl_out_y}`,
            // Left diagonal down to apex
            `L ${apex_in_x},${apex_in_y}`,
            `Q ${width / 2},${notchDepth} ${apex_out_x},${apex_out_y}`,
            // Right diagonal up to top-right
            `L ${tr_in_x},${tr_in_y}`,
            `A ${r},${r} 0 0 1 ${tr_out_x},${tr_out_y}`,
            // Right side down to bottom-right
            `L ${br_in_x},${br_in_y}`,
            `A ${r},${r} 0 0 1 ${br_out_x},${br_out_y}`,
            // Bottom to bottom-left
            `L ${bl_in_x},${bl_in_y}`,
            `A ${r},${r} 0 0 1 ${bl_out_x},${bl_out_y}`,
            // Left side up to top-left
            `L ${tl_in_x},${tl_in_y}`,
            `A ${r},${r} 0 0 1 ${tl_out_x},${tl_out_y}`,
            'Z'
        ].join(' ');

        const path = svg('path', {
            d: pathData,
            fill: '#FFF9C4', // Light yellow
            stroke: '#F57F17', // Dark yellow/amber
            'stroke-width': '2',
            'stroke-linejoin': 'round',
            'stroke-linecap': 'round'
        });

        // Label text (offset down slightly to account for notch)
        const text = svg('text', {
            x: (width / 2).toString(),
            y: ((height + notchDepth) / 2).toString(),
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': '11',
            'font-weight': 'bold'
        }, label);
        labelClass(text);

        const children = context.renderChildren(node);
        const vnode = svg('g', {});
        vnode.children = [path, text, ...children];

        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => setAttr(vnode, 'class', cssClass));
        }

        //console.log('✅ Rendered inner name (V-notch ∧ receiving):', label);
        return vnode;
    }
}

/**
 * Custom view for Outer Names (output interface)
 * Shape: Arrow pointing DOWN/OUTWARD from BOTTOM - represents "output" or "external"
 *   ┌─────────┐
 *   │  name   │
 *   └────┬────┘
 *        ▼
 */
@injectable()
export class BigraphOuterNameView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        //console.log('📍 Rendering outer name:', node.id, 'with args:', node.args);

        const label = typeof node.args?.label === 'string' ? node.args.label : 'OuterName';
        const width = Math.max(node.size.width, 0);
        const height = Math.max(node.size.height, 0);
        const tipSize = 10; // Size of the arrow tip
        const cornerRadius = 6; // Rounded corner radius

        // Pentagon with arrow at BOTTOM pointing outward (down)
        // Using a path with rounded corners
        // Start from top-left (after rounded corner)
        const pathData = [
            `M ${cornerRadius},0`,                      // Move to start (after top-left rounded corner)
            `L ${width - cornerRadius},0`,              // Line to before top-right corner
            `A ${cornerRadius},${cornerRadius} 0 0 1 ${width},${cornerRadius}`, // Top-right rounded corner
            `L ${width},${height - tipSize}`,           // Line to bottom right before tip
            `L ${width / 2},${height}`,                 // Bottom center - arrow tip pointing OUT
            `L 0,${height - tipSize}`,                  // Line to bottom left before tip
            `L 0,${cornerRadius}`,                      // Line to before top-left corner
            `A ${cornerRadius},${cornerRadius} 0 0 1 ${cornerRadius},0`, // Top-left rounded corner
            'Z'                                         // Close path
        ].join(' ');

        const path = svg('path', {
            d: pathData,
            fill: '#E1F5FE', // Light blue
            stroke: '#01579B', // Dark blue
            'stroke-width': '2',
            'stroke-linejoin': 'round',
            'stroke-linecap': 'round'
        });

        // Label text (offset up slightly to account for tip)
        const text = svg('text', {
            x: (width / 2).toString(),
            y: ((height - tipSize) / 2).toString(),
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': '11',
            'font-weight': 'bold'
        }, label);
        labelClass(text);

        const children = context.renderChildren(node);
        const vnode = svg('g', {});
        vnode.children = [path, text, ...children];

        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => setAttr(vnode, 'class', cssClass));
        }

        //console.log('✅ Rendered outer name (arrow ▼ outward):', label);
        return vnode;
    }
}

/**
 * Custom view for bigraph nodes that applies proper coloring based on node type
 */
@injectable()
export class BigraphCustomNodeView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        //console.log('🎨 Rendering bigraph custom node:', node.id, 'with args:', node.args);

        // Get color/label from node arguments
        const color = typeof node.args?.color === 'string' ? node.args.color : undefined;
        const label = typeof node.args?.label === 'string' ? node.args.label : 'Node';

        const rectAttrs: any = {
            x: '0',
            y: '0',
            width: Math.max(node.size.width, 0).toString(),
            height: Math.max(node.size.height, 0).toString(),
            rx: '6', // Rounded corners
            ry: '6'
        };

        const textAttrs: any = {
            x: (node.size.width / 2).toString(),
            y: (node.size.height / 2).toString(),
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': '12'
        };

        // Only apply specific colors if provided in meta-information
        if (color) {
            rectAttrs.fill = color;
            rectAttrs.stroke = this.darkenColor(color);
            rectAttrs['stroke-width'] = '2';
            textAttrs.fill = this.getContrastColor(color);
        }

        // Create the main rectangle
        const rect = svg('rect', rectAttrs);

        // Create label text
        const text = svg('text', textAttrs, label);
        if (!color) {
            labelClass(text);
        }

        // Render children
        const children = context.renderChildren(node);
        
        // Main container group
        const vnode = svg('g', {});
        vnode.children = [rect, text, ...children];

        // Apply CSS classes from server
        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => {
                setAttr(vnode, 'class', cssClass);
            });
        }

        //console.log('✅ Rendered bigraph node with color:', color);
        return vnode;
    }

    /**
     * Darken a color by 20% for borders
     */
    private darkenColor(hex: string): string {
        if (!hex.startsWith('#') || hex.length !== 7) {
            return '#000000';
        }
        
        try {
            const r = parseInt(hex.slice(1, 3), 16);
            const g = parseInt(hex.slice(3, 5), 16);
            const b = parseInt(hex.slice(5, 7), 16);
            
            const darkerR = Math.max(0, Math.floor(r * 0.8));
            const darkerG = Math.max(0, Math.floor(g * 0.8));
            const darkerB = Math.max(0, Math.floor(b * 0.8));
            
            return `#${darkerR.toString(16).padStart(2, '0')}${darkerG.toString(16).padStart(2, '0')}${darkerB.toString(16).padStart(2, '0')}`;
        } catch (e) {
            return '#000000';
        }
    }

    /**
     * Black/white text on arbitrary control fill so labels stay readable.
     */
    private getContrastColor(hex: string): string {
        if (!hex.startsWith('#') || hex.length !== 7) {
            return '#000000';
        }
        try {
            const r = parseInt(hex.slice(1, 3), 16);
            const g = parseInt(hex.slice(3, 5), 16);
            const b = parseInt(hex.slice(5, 7), 16);
            const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
            return luminance > 0.5 ? '#000000' : '#ffffff';
        } catch {
            return '#000000';
        }
    }
}

/**
 * Custom view for Root Nodes (small grey circle)
 */
@injectable()
export class BigraphRootNodeView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        //console.log('🌳 Rendering root node:', node.id);

        const label = typeof node.args?.label === 'string' ? node.args.label : 'Root';

        // Circle for root
        const circle = svg('circle', {
            cx: (node.size.width / 2).toString(),
            cy: (node.size.height / 2).toString(),
            r: (Math.min(node.size.width, node.size.height) / 2).toString(),
            fill: '#BDBDBD', // Grey
            stroke: '#616161', // Darker grey
            'stroke-width': '2'
        });

        // Label text
        const text = svg('text', {
            x: (node.size.width / 2).toString(),
            y: (node.size.height / 2).toString(),
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': '10',
            'font-weight': 'bold'
        }, label);
        labelClass(text);

        const children = context.renderChildren(node);
        const vnode = svg('g', {});
        vnode.children = [circle, text, ...children];

        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => setAttr(vnode, 'class', cssClass));
        }

        return vnode;
    }
}

/**
 * Custom view for Sites (place-graph placeholders / slots).
 * Renders a dashed rectangle to convey "empty slot" semantics.
 */
@injectable()
export class BigraphSiteView extends ShapeView {
    render(node: Readonly<GNode & ArgsAware>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        const label = typeof node.args?.label === 'string' ? node.args.label : 'S';
        const w = Math.max(node.size?.width ?? 70, 0);
        const h = Math.max(node.size?.height ?? 45, 0);
        const filled = node.cssClasses?.includes('bigraph-site-filled') ?? false;

        const rect = svg('rect', {
            x: '0',
            y: '0',
            width: w.toString(),
            height: h.toString(),
            // Empty: warm orange tint, dashed border — the classic "slot" look
            // Filled: subtle transparent container, solid thin border
            fill: filled ? 'rgba(255,255,255,0.05)' : 'rgba(255,165,0,0.15)',
            stroke: filled ? 'rgba(150,150,150,0.6)' : '#ff9800',
            'stroke-width': '2',
            'stroke-dasharray': filled ? '0' : '6,4',
            rx: '6',
            ry: '6'
        });

        // Empty site: large centered white label
        // Filled site: small italic label top-left as caption
        const text = svg('text', {
            x: filled ? '12' : (w / 2).toString(),
            y: filled ? '14' : (h / 2).toString(),
            'text-anchor': filled ? 'start' : 'middle',
            'dominant-baseline': 'central',
            'font-family': 'Arial, sans-serif',
            'font-size': filled ? '11' : '14',
            'font-weight': 'bold',
            'font-style': filled ? 'italic' : 'normal',
            opacity: '1'
        }, label);
        labelClass(text);

        const children = context.renderChildren(node);
        const vnode = svg('g', {});
        vnode.children = [rect, text, ...children];

        if (node.cssClasses) {
            node.cssClasses.forEach(cssClass => setAttr(vnode, 'class', cssClass));
        }

        return vnode;
    }
}

/**
 * No-op handler for server→client evolution actions that are consumed by connector.ts
 * before reaching the diagram. Registered to suppress "Missing handler" errors.
 */
@injectable()
class EvolutionActionSink implements IActionHandler {
    handle(_action: Action): void { /* intentionally empty */ }
}

/**
 * Fires a `bigraph-palette-refresh` window event when the model changes so the
 * custom palette can re-fetch creation tools from the server (e.g. after a new
 * control was added to the signature).
 */
let modelUpdateRefreshTimer: ReturnType<typeof setTimeout> | undefined;
@injectable()
class ModelUpdatePaletteRefreshHandler implements IActionHandler {
    handle(_action: Action): void {
        clearTimeout(modelUpdateRefreshTimer);
        modelUpdateRefreshTimer = setTimeout(() => {
            window.dispatchEvent(new CustomEvent('bigraph-palette-refresh'));
        }, 600);
    }
}

const TOOLBAR_SHAKE_CLASS = 'bigraph-palette-shake';
const TOOLBAR_PALETTE_SELECTORS = ['.bigraph-palette', '.tool-palette', '.sprotty-palette', '.glsp-palette'];
const TOOLBAR_INTERACTION_TIMEOUT_MS = 3000;

function findToolPalette(): Element | null {
    for (const sel of TOOLBAR_PALETTE_SELECTORS) {
        const el = document.querySelector(sel);
        if (el) { return el; }
    }
    return null;
}

/**
 * Tracks whether the user's last interaction was with the tool palette.
 *
 * When the user clicks a tool palette item (mousedown on .tool-palette) we set
 * a short-lived flag. If a server WARNING/ERROR arrives while the flag is active
 * the palette shake animation is triggered. The flag auto-expires after 3 s.
 */
class ToolbarInteractionTracker {
    private _active = false;
    private _timer: ReturnType<typeof setTimeout> | undefined;

    constructor() {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.attachListeners());
        } else {
            this.attachListeners();
        }
    }

    /** Called by the app when a server WARNING/ERROR arrives — shakes only if the palette was recently used. */
    shakeIfActive(): void {
        console.log('[ToolbarTracker] shakeIfActive called, flag active:', this._active);
        if (this.consume()) {
            this.shakePalette();
        }
    }

    private consume(): boolean {
        if (!this._active) { return false; }
        this.deactivate();
        return true;
    }

    private shakePalette(): void {
        const palette = findToolPalette();
        if (!palette) { return; }
        palette.classList.remove(TOOLBAR_SHAKE_CLASS);
        void (palette as HTMLElement).offsetWidth; // force reflow to restart animation
        palette.classList.add(TOOLBAR_SHAKE_CLASS);
        palette.addEventListener('animationend', () => {
            palette.classList.remove(TOOLBAR_SHAKE_CLASS);
        }, { once: true });
    }

    private attachListeners(): void {
        // Fallback: listen on document and check if the click originated inside the palette.
        // This works even if the palette element isn't in the DOM yet when we attach.
        document.addEventListener('mousedown', (e) => {
            const palette = findToolPalette();
            if (palette && palette.contains(e.target as Node)) {
                this.activate();
            }
        });
        console.log('[ToolbarTracker] Document-level mousedown listener attached');
    }

    private activate(): void {
        console.log('[ToolbarTracker] Palette interaction detected — flag active for', TOOLBAR_INTERACTION_TIMEOUT_MS, 'ms');
        this._active = true;
        clearTimeout(this._timer);
        this._timer = setTimeout(() => this.deactivate(), TOOLBAR_INTERACTION_TIMEOUT_MS);
    }

    private deactivate(): void {
        this._active = false;
        clearTimeout(this._timer);
    }
}

export const toolbarInteractionTracker = new ToolbarInteractionTracker();

/**
 * Receives the custom bigraph.paletteShake action injected by the connector
 * whenever the server emits a WARNING or ERROR MessageAction.
 * Only shakes when the user had recently clicked the palette.
 */
@injectable()
class PaletteShakeHandler implements IActionHandler {
    handle(_action: Action): void {
        console.log('[PaletteShakeHandler] received bigraph.paletteShake');
        toolbarInteractionTracker.shakeIfActive();
    }
}

/**
 * Bigraph diagram module that configures custom views
 */
const bigraphDiagramModule = new ContainerModule((bind, unbind, isBound, rebind) => {
    const context = { bind, unbind, isBound, rebind };
    
    console.log('🔧 Configuring bigraph diagram module');
    
    // Configure custom view for bigraph nodes
    // Map the server's "bigraph:node" type to our custom view
    configureModelElement(context, 'bigraph:node', GNode, BigraphCustomNodeView);
    
    // Also configure fallback for generic "node" type
    configureModelElement(context, 'node', GNode, BigraphCustomNodeView);
    
    // Configure custom view for hyper edge nodes (green text, no frame)
    // Note: server sends "bigraph:hyperedge" (no hyphen)
    configureModelElement(context, 'bigraph:hyperedge', GNode, BigraphHyperEdgeView);
    
    // Configure custom views for Inner and Outer Names
    configureModelElement(context, 'bigraph:inner-name', GNode, BigraphInnerNameView);
    configureModelElement(context, 'bigraph:outer-name', GNode, BigraphOuterNameView);
    
    // Configure custom view for Root Nodes
    configureModelElement(context, 'bigraph:root', GNode, BigraphRootNodeView);
    
    // Configure custom view for Sites (place-graph slots)
    configureModelElement(context, 'bigraph:site', GNode, BigraphSiteView);
    
    // Configure default edge views for bigraph edges
    configureDefaultModelElements(context);
    
    // Configure Place Graph edges with custom view that ensures marker exists
    configureModelElement(context, 'bigraph:place-edge', GEdge, BigraphPlaceEdgeView);
    
    // Configure Link Graph connections (connects nodes to edges, outer names, inner names)
    configureModelElement(context, 'bigraph:link-connection', GEdge, PolylineEdgeView);
    
    // Configure Hyper Edge connections (legacy)
    configureModelElement(context, 'bigraph:hyper-connection', GEdge, PolylineEdgeView);
    
    // Configure Outer Name connections (legacy)
    configureModelElement(context, 'bigraph:outer-connection', GEdge, PolylineEdgeView);
    
    console.log('✅ Bigraph diagram module configured for types: bigraph:node, bigraph:hyperedge, bigraph:inner-name, bigraph:outer-name, bigraph:root, bigraph:link-connection, edges');

    // Silence "Missing handler" errors for server→client evolution actions.
    // These are consumed by connector.ts; the diagram itself has nothing to do with them.
    configureActionHandler(context, 'bigraph.evolutionStarted', EvolutionActionSink);
    configureActionHandler(context, 'bigraph.evolutionFinished', EvolutionActionSink);

    // Shake the tool palette when a canvas/toolbar operation fails.
    // The connector injects a bigraph.paletteShake action for every WARNING/ERROR;
    // the handler only shakes if the user had recently clicked the palette.
    configureActionHandler(context, 'bigraph.paletteShake', PaletteShakeHandler);

    // Re-fetch palette creation tools when the server pushes a model update.
    configureActionHandler(context, SetModelAction.KIND, ModelUpdatePaletteRefreshHandler);
    configureActionHandler(context, UpdateModelAction.KIND, ModelUpdatePaletteRefreshHandler);

    // Configure context menu provider
    if (isBound(TYPES.IContextMenuItemProvider)) {
        context.rebind(TYPES.IContextMenuItemProvider).to(BigraphContextMenuProvider).inSingletonScope();
    } else {
        context.bind(TYPES.IContextMenuItemProvider).to(BigraphContextMenuProvider).inSingletonScope();
    }

    // Configure context menu service (The UI part)
    if (isBound(TYPES.IContextMenuService)) {
        context.rebind(TYPES.IContextMenuService).to(BigraphContextMenuService).inSingletonScope();
    } else {
        context.bind(TYPES.IContextMenuService).to(BigraphContextMenuService).inSingletonScope();
    }
});

export function createBigraphDiagramContainer(...containerConfiguration: ContainerConfiguration): Container {
    console.log('🔧 Creating bigraph diagram container');
    
    const container = initializeDiagramContainer(new Container(), ...containerConfiguration);
    
    // Configure default model elements first
    configureDefaultModelElements(container);
    
    // Load standard contextMenuModule to enable right-click support
    container.load(contextMenuModule);
    
    // Then load our custom bigraph module
    container.load(bigraphDiagramModule);
    
    console.log('✅ Bigraph container created with custom views');
    
    return container;
} 