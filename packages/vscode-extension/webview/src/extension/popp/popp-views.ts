import {
    ShapeView,
    RenderingContext,
    GNode,
    GEdge,
    ArgsAware,
    PolylineEdgeView,
    Point,
    angleOfPoint,
    toDegrees,
    svg
} from '@eclipse-glsp/client';
import { injectable } from 'inversify';
import { h, VNode } from 'snabbdom';
import { setClass } from 'sprotty';

const NODE_ICONS: Record<string, string> = {
    'popp:problem': 'warning',
    'popp:goal': 'target',
    'popp:consequence': 'zap',
    'popp:solution': 'lightbulb',
    'popp:success_criteria': 'unverified',
    'popp:success_proof': 'verified'
};

const RELATION_TEXT: Record<string, string> = {
    'popp:causes': 'causes',
    'popp:inverts': 'inverts',
    'popp:realizes': 'realizes',
    'popp:produces': 'produces',
    'popp:validates': 'validates'
};

const AND_ARC_RADIUS = 30;

const POPP_NODE_CLASS = 'popp-node';
const POPP_NODE_BOX_CLASS = 'popp-node-box';
const POPP_NODE_ICON_CLASS = 'popp-node-icon';
const POPP_ARROW_CLASS = 'popp-arrow';
const POPP_RELATION_LABEL_CLASS = 'popp-relation-label';
const POPP_AND_ARC_CLASS = 'popp-and-arc';

// ---------------------------------------------------------------------------
// Tree nodes: rounded box + codicon in the top-left corner + description label
// ---------------------------------------------------------------------------
@injectable()
export class POPPNodeView extends ShapeView {
    override render(node: Readonly<GNode & ArgsAware>, context: RenderingContext): VNode | undefined {
        if (!this.isVisible(node, context)) {
            return undefined;
        }

        const width = Math.max(node.size?.width ?? 180, 100);
        const height = Math.max(node.size?.height ?? 60, 40);
        const icon = NODE_ICONS[node.type] ?? 'circle-outline';

        const box = svg('rect', {
            x: '0',
            y: '0',
            rx: '8',
            ry: '8',
            width: width.toString(),
            height: height.toString()
        });
        setClass(box, POPP_NODE_BOX_CLASS, true);

        const iconSpan = h('span', { class: { codicon: true, [`codicon-${icon}`]: true } });
        const iconObject = svg('foreignObject', { x: '10', y: '10', width: '20', height: '20' }, iconSpan);
        setClass(iconObject, POPP_NODE_ICON_CLASS, true);

        const textContent = node.children.find(c => c.type === 'popp:node_description') as any;
        const textString = textContent?.text || '';

        const textDiv = h('div', {
            style: {
                fontFamily: 'inherit',
                fontSize: '12px',
                color: 'var(--vscode-editor-foreground, #ccc)',
                wordWrap: 'break-word',
                whiteSpace: 'normal',
                overflow: 'hidden',
                padding: '2px'
            },
            contentEditable: 'true',
            onBlur: (e: FocusEvent) => {
                const target = e.target as HTMLDivElement;
                const newText = target.innerText;
                // TODO edit description action
            }
        }, textString);

        const textForeignObject = svg('foreignObject', {
            x: '35',
            y: '8',
            width: (width - 45).toString(),
            height: (height - 16).toString()
        }, textDiv);

        const group = svg('g', { 'data-popp-type': node.type });
        setClass(group, POPP_NODE_CLASS, true);
        setClass(group, 'selected', node.selected);
        setClass(group, 'mouseover', node.hoverFeedback);
        
        group.children = [box, iconObject, textForeignObject];
        return group;
    }
}

// ---------------------------------------------------------------------------
// Relations: line + arrow head + relation name on the line
// ---------------------------------------------------------------------------
@injectable()
export class POPPRelationEdgeView extends PolylineEdgeView {
    protected override renderAdditionals(edge: GEdge, segments: Point[], context: RenderingContext): VNode[] {
        const additionals = super.renderAdditionals(edge, segments, context);
        const p1 = segments[segments.length - 2];
        const p2 = segments[segments.length - 1];

        // arrow head
        const angle = toDegrees(angleOfPoint({ x: p1.x - p2.x, y: p1.y - p2.y }));
        const arrow = svg('path', {
            d: 'M 1,0 L 11,-5 L 11,5 Z',
            transform: `rotate(${angle} ${p2.x} ${p2.y}) translate(${p2.x} ${p2.y})`
        });
        setClass(arrow, POPP_ARROW_CLASS, true);
        additionals.push(arrow);

        // relation name at the middle segment
        const text = RELATION_TEXT[edge.type] ?? edge.type;
        const i = Math.floor((segments.length - 1) / 2);
        const a = segments[i];
        const b = segments[i + 1];
        const mx = (a.x + b.x) / 2;
        const my = (a.y + b.y) / 2;
        const w = text.length * 6.5 + 10;

        const bg = svg('rect', {
            x: (mx - w / 2).toString(),
            y: (my - 9).toString(),
            width: w.toString(),
            height: '18',
            rx: '4',
            ry: '4'
        });
        const label = svg(
            'text',
            {
                x: mx.toString(),
                y: my.toString(),
                dy: '0.35em',
                'text-anchor': 'middle'
            },
            text
        );
        const labelGroup = svg('g', {});
        setClass(labelGroup, POPP_RELATION_LABEL_CLASS, true);
        labelGroup.children = [bg, label];
        additionals.push(labelGroup);

        return additionals;
    }
}

// ---------------------------------------------------------------------------
// Decompositions: plain line; AND additionally gets an arc across the siblings
// ---------------------------------------------------------------------------
@injectable()
export class POPPOrDecompositionEdgeView extends PolylineEdgeView {
    // plain line, no arrow, nothing else
}

@injectable()
export class POPPAndDecompositionEdgeView extends PolylineEdgeView {
    protected override renderAdditionals(edge: GEdge, segments: Point[], context: RenderingContext): VNode[] {
        const additionals = super.renderAdditionals(edge, segments, context);
        const source = edge.source;
        if (!source || segments.length < 2) {
            return additionals;
        }

        // all AND edges of the same parent, sorted by their departure angle
        const siblings = Array.from(source.outgoingEdges)
            .filter(e => e.type === edge.type)
            .map(e => ({ id: e.id, route: this.edgeRouterRegistry?.route(e) ?? [] }))
            .filter(s => s.route.length >= 2)
            .map(s => ({
                ...s,
                angle: Math.atan2(s.route[1].y - s.route[0].y, s.route[1].x - s.route[0].x)
            }))
            .sort((a, b) => b.angle - a.angle);

        const idx = siblings.findIndex(s => s.id === edge.id);
        // each edge draws the arc piece up to its next sibling -> together one continuous arc
        if (idx < 0 || idx >= siblings.length - 1) {
            return additionals;
        }

        const start = pointAlong(siblings[idx].route, AND_ARC_RADIUS);
        const end = pointAlong(siblings[idx + 1].route, AND_ARC_RADIUS);
        const origin = siblings[idx].route[0];
        const cross = (start.x - origin.x) * (end.y - origin.y) - (start.y - origin.y) * (end.x - origin.x);
        const sweep = cross > 0 ? 1 : 0;

        const arc = svg('path', {
            d: `M ${start.x},${start.y} A ${AND_ARC_RADIUS},${AND_ARC_RADIUS} 0 0 ${sweep} ${end.x},${end.y}`
        });
        setClass(arc, POPP_AND_ARC_CLASS, true);
        additionals.push(arc);

        return additionals;
    }
}

/** Point at the given distance from the start of a route (walks along the polyline). */
function pointAlong(route: Point[], distance: number): Point {
    let remaining = distance;
    for (let i = 0; i < route.length - 1; i++) {
        const a = route[i];
        const b = route[i + 1];
        const len = Math.hypot(b.x - a.x, b.y - a.y);
        if (len >= remaining && len > 0) {
            const t = remaining / len;
            return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
        }
        remaining -= len;
    }
    return route[route.length - 1];
}