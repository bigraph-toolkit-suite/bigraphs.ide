import { injectable } from 'inversify';
import { IContextMenuItemProvider, LabeledAction, GModelRoot, Point, GNode, Action, GModelElement } from '@eclipse-glsp/client';

@injectable()
export class BigraphContextMenuProvider implements IContextMenuItemProvider {
    
    getItems(root: Readonly<GModelRoot>, lastMousePosition?: Point): Promise<LabeledAction[]> {
        const actions: LabeledAction[] = [];
        
        if (!lastMousePosition) {
            return Promise.resolve([]);
        }
        
        // `lastMousePosition` is provided by Sprotty's context-menu mouse listener as `event.x/y`,
        // which are in client coordinates. Model element positions/sizes are in the model's
        // (viewport-local) coordinate system, so we must convert first (pan/zoom aware).
        const pointOnDiagram = (root as any).parentToLocal
            ? (root as any).parentToLocal(lastMousePosition)
            : lastMousePosition;

        const element = this.findElementAt(root, pointOnDiagram);
        
        if (element) {
            // Nodes and Names: Enable Rename
            // Check for both 'node' and 'bigraph:node' since the diagram module configures both
            if (element.type === 'bigraph:node' || element.type === 'node' || 
                element.type === 'bigraph:inner-name' || element.type === 'bigraph:outer-name') {
                actions.push({
                    label: 'Rename',
                    actions: [{
                        kind: 'localRename',
                        elementId: element.id
                    } as Action]
                });
            }
            // HyperEdges: Show "Not available" (disabled)
            else if (element.type === 'bigraph:hyperedge') {
                actions.push({
                    label: 'Not available',
                    actions: [] // No actions
                });
            }
        }

        return Promise.resolve(actions);
    }
    
    private findElementAt(root: GModelRoot, point: Point): GNode | undefined {
        // Find the deepest (smallest area) element at the point.
        // Nested elements (e.g. nodes inside a site) have position relative to their parent;
        // we must accumulate parent offsets to get canvas coordinates for bounds check.
        let bestMatch: GNode | undefined = undefined;
        let smallestArea = Infinity;

        // Only consider model elements that can actually produce menu entries.
        // This prevents returning container elements (e.g. sites) that have a size/position
        // but do not map to Rename/Not available actions.
        const eligibleTypes = new Set<string>([
            'bigraph:node',
            'node',
            'bigraph:inner-name',
            'bigraph:outer-name',
            'bigraph:hyperedge'
        ]);

        // Small tolerance for numeric imprecision at boundaries.
        const HIT_EPSILON = 2;

        // Stack: [element, parentOffsetX, parentOffsetY]
        const stack: [GModelElement, number, number][] = (root.children || []).map(c => [c, 0, 0]);

        while (stack.length > 0) {
            const [child, parentOffsetX, parentOffsetY] = stack.pop()!;
            const c = child as any;

            // We need `position` to correctly accumulate absolute coordinates for descendants.
            // `size` may be missing for some container elements, but children might still be hittable.
            if (!c.position) {
                continue;
            }

            const relX = c.position.x ?? 0;
            const relY = c.position.y ?? 0;
            const absX = parentOffsetX + relX;
            const absY = parentOffsetY + relY;

            const w = c.size?.width ?? 0;
            const h = c.size?.height ?? 0;

            const inBounds =
                c.size &&
                point.x >= absX - HIT_EPSILON &&
                point.x <= absX + w + HIT_EPSILON &&
                point.y >= absY - HIT_EPSILON &&
                point.y <= absY + h + HIT_EPSILON;

            if (inBounds && eligibleTypes.has(String(c.type))) {
                const area = w * h;
                if (area < smallestArea) {
                    smallestArea = area;
                    bestMatch = child as GNode;
                }
            }

            // Always traverse children if present; `size` might be missing for `c` itself.
            if (c.children?.length) {
                for (const ch of c.children) {
                    stack.push([ch, absX, absY]);
                }
            }
        }

        return bestMatch;
    }
}
