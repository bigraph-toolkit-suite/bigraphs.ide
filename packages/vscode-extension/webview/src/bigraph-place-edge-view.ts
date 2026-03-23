/**
 * Custom view for Place Graph edges with arrow markers pointing to parent
 */
import { PolylineEdgeView, RenderingContext, IViewArgs, GEdge, setAttr } from '@eclipse-glsp/client';
import { injectable } from 'inversify';
import { VNode } from 'snabbdom';
import { ensureArrowheadMarker } from './svg-marker-utils';

@injectable()
export class BigraphPlaceEdgeView extends PolylineEdgeView {
    private static markerAdded = false;

    render(edge: Readonly<GEdge>, context: RenderingContext, args?: IViewArgs): VNode | undefined {
        // Get the base edge rendering from PolylineEdgeView (it handles visibility)
        const vnode = super.render(edge, context, args);
        
        if (!vnode) {
            return undefined;
        }

        // Ensure marker definition exists in SVG (only add once)
        if (!BigraphPlaceEdgeView.markerAdded) {
            this.ensureMarkerDefinition();
            BigraphPlaceEdgeView.markerAdded = true;
        }

        // Recursively find and update path elements to add marker-start and rounded corners
        const addMarkerToPath = (node: VNode): void => {
            if (!node || typeof node !== 'object') {
                return;
            }
            
            // Check if this is a path element - VNode sel can be a string selector
            const sel = typeof node.sel === 'string' ? node.sel : '';
            if (sel === 'path') {
                setAttr(node, 'marker-start', 'url(#arrowhead)');
                setAttr(node, 'stroke-linecap', 'round');
                setAttr(node, 'stroke-linejoin', 'round');
            }
            
            // Recursively process children
            if (node.children && Array.isArray(node.children)) {
                for (const child of node.children) {
                    if (typeof child === 'object') {
                        addMarkerToPath(child);
                    }
                }
            }
        };

        addMarkerToPath(vnode);
        
        // Also try to set marker-start and rounded corners directly on the vnode if it's a path
        const vnodeSel = typeof vnode.sel === 'string' ? vnode.sel : '';
        if (vnodeSel === 'path') {
            setAttr(vnode, 'marker-start', 'url(#arrowhead)');
            setAttr(vnode, 'stroke-linecap', 'round');
            setAttr(vnode, 'stroke-linejoin', 'round');
        }

        return vnode;
    }

    private ensureMarkerDefinition(): void {
        // Add marker definition to SVG root using DOM manipulation
        // This runs after the initial render
        setTimeout(() => {
            const svgElement = document.querySelector('svg');
            if (svgElement) {
                ensureArrowheadMarker(svgElement);
            }
        }, 100);
    }
}
