/**
 * Utility functions for managing SVG marker definitions
 */

/**
 * Creates the arrowhead marker definition for place graph edges
 */
export function createArrowheadMarker(): SVGMarkerElement {
    const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
    marker.setAttribute('id', 'arrowhead');
    marker.setAttribute('markerWidth', '5');
    marker.setAttribute('markerHeight', '5');
    marker.setAttribute('refX', '0.5');
    marker.setAttribute('refY', '2.5');
    marker.setAttribute('orient', 'auto-start-reverse');
    marker.setAttribute('markerUnits', 'strokeWidth');
    marker.setAttribute('viewBox', '0 0 5 5');
    
    const arrowPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    arrowPath.setAttribute('d', 'M0,0 L4,2.5 L0,5 z');
    arrowPath.setAttribute('fill', '#2196F3');
    arrowPath.setAttribute('stroke', '#2196F3');
    arrowPath.setAttribute('stroke-width', '0.3');
    
    marker.appendChild(arrowPath);
    return marker;
}

/**
 * Ensures the arrowhead marker exists in the SVG defs
 */
export function ensureArrowheadMarker(svgElement: SVGElement): void {
    let defs = svgElement.querySelector('defs');
    if (!defs) {
        defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
        svgElement.insertBefore(defs, svgElement.firstChild);
    }
    
    if (!defs.querySelector('#arrowhead')) {
        const marker = createArrowheadMarker();
        defs.appendChild(marker);
        console.log('✅ Arrowhead marker created in SVG defs');
    }
}
