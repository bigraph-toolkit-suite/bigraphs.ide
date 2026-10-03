import {
    ShapeView,
    RenderingContext,
    GNode,
    GEdge,
    GLabel,
    GPort,
    ArgsAware,
    PolylineEdgeView,
    Point,
    angleOfPoint,
    toDegrees,
    svg,
} from '@eclipse-glsp/client';
import { injectable } from 'inversify';
import { h, VNode } from 'snabbdom';
import { setClass } from 'sprotty';

export const POPP_DESCRIPTION_LABEL_CLASS = 'popp-node-description';

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

        const box = svg('rect', { x: '0', y: '0', rx: '8', ry: '8', width: width.toString(), height: height.toString() });
        setClass(box, POPP_NODE_BOX_CLASS, true);

        const iconSpan = h('span', { class: { codicon: true, [`codicon-${icon}`]: true } });
        const iconObject = svg('foreignObject', { x: '10', y: '10', width: '20', height: '20' }, iconSpan);
        setClass(iconObject, POPP_NODE_ICON_CLASS, true);

        const group = svg('g', { 'data-popp-type': node.type });
        setClass(group, POPP_NODE_CLASS, true);
        setClass(group, 'selected', node.selected);
        setClass(group, 'mouseover', node.hoverFeedback);

        group.children = [box, iconObject, ...context.renderChildren(node)];
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
// Decompositions: plain line + port
// ---------------------------------------------------------------------------
@injectable()
export class POPPDecompositionEdgeView extends PolylineEdgeView {
    // plain line, no arrow, nothing else
}

@injectable()
export class POPPDecompositionPortView extends ShapeView {
    override render(port: Readonly<GPort & ArgsAware>, context: RenderingContext): VNode | undefined {
        if (!this.isVisible(port, context)) { return undefined; }
        // leaf nodes have no children, so no diamond
        if (Array.from(port.outgoingEdges).length === 0) { return undefined; }

        const isAnd = String(port.args?.decomposition_type ?? '').toLowerCase() === 'and';
        const diamond = svg('path', { d: 'M 6,0 L 12,8 L 6,16 L 0,8 Z' });
        setClass(diamond, 'popp-decomp-diamond', true);
        setClass(diamond, isAnd ? 'popp-decomp-and' : 'popp-decomp-or', true);

        const g = svg('g', {});
        g.children = [diamond];
        return g;
    }
}

// ---------------------------------------------------------------------------
// Node Descriptions: Label with the description of the node
// ---------------------------------------------------------------------------
const CHAR_W = 6.5;
const LINE_H = 16;

function wrap(text: string, maxChars: number): string[] {
    const lines: string[] = [];
    let cur = '';
    for (const w of text.trim().split(/\s+/)) {
        if (cur && cur.length + 1 + w.length > maxChars) { lines.push(cur); cur = ''; }
        cur = cur ? cur + ' ' + w : w;
    }
    lines.push(cur);
    return lines;
}

@injectable()
export class POPPDescriptionLabelView extends ShapeView {
    override render(label: Readonly<GLabel>, context: RenderingContext): VNode | undefined {
        if (!this.isVisible(label, context)) { return undefined; }

        const w = label.size.width;
        const h = label.size.height;
        const lines = wrap(label.text ?? '', Math.max(1, Math.floor(w / CHAR_W)));

        const hit = svg('rect', { x: '0', y: '0', width: w.toString(), height: h.toString(), fill: 'transparent' });

        const text = svg('text', {
            'font-size': '12',
            'font-family': 'Arial, sans-serif',
            'dominant-baseline': 'central',
            style: { textAnchor: 'middle', fill: 'var(--vscode-editor-foreground)' }
        });
       text.children = lines.map((line, i) =>
            svg('tspan', {
                x: (w / 2).toString(),
                y: (i * LINE_H + LINE_H / 2).toString()
            }, line)
        );

        const g = svg('g', {});
        setClass(g, POPP_DESCRIPTION_LABEL_CLASS, true);
        g.children = [hit, text];
        return g;
    }
}