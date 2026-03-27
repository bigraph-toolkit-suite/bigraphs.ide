import type { EvolutionOperation, VsCodeApi } from './types.js';

interface TreeNode {
	id: string;
	type: string;
	date: string;
	result: string | null;
	predecessor: string | null;
	rule: string | null;
	x: number;
	y: number;
}

interface TreeEdge {
	from: TreeNode;
	to: TreeNode;
}

interface TreeLayout {
	nodes: TreeNode[];
	edges: TreeEdge[];
	nodeMap: Record<string, TreeNode>;
}

const NODE_R  = 14;
const LEVEL_H = 70;
const MIN_SEP = 40;

export class EvoTree {
	private readonly sectionEl: HTMLElement;
	private readonly svgEl: SVGSVGElement;
	private readonly wrapEl: HTMLElement;
	private readonly tooltipEl: HTMLElement;
	private readonly ctxMenuEl: HTMLDivElement;
	private readonly vscode: VsCodeApi;

	private ops: EvolutionOperation[]   = [];
	private cursor: string | null       = null;
	private scale                       = 1;
	private offX                        = 0;
	private offY                        = 0;
	private dragging                    = false;
	private dragStartX                  = 0;
	private dragStartY                  = 0;
	private dragOffX                    = 0;
	private dragOffY                    = 0;

	constructor(vscode: VsCodeApi) {
		this.vscode     = vscode;
		this.sectionEl  = document.getElementById('tree-section')!;
		this.svgEl      = document.getElementById('tree-svg') as unknown as SVGSVGElement;
		this.wrapEl     = document.getElementById('tree-canvas-wrap')!;
		this.tooltipEl  = document.getElementById('tree-tooltip')!;
		this.ctxMenuEl  = this.createContextMenu();
		this.initControls();
	}

	// ── Public API ──────────────────────────────────────────────────────────

	update(ops: EvolutionOperation[], cursorId: string | null): void {
		this.ops    = ops       ?? [];
		this.cursor = cursorId  ?? null;
		const hasOps = this.ops.length > 0;
		this.sectionEl.classList.toggle('visible', hasOps);
		if (!hasOps) { return; }
		this.scale = 1;
		this.centerOnCursor();
		this.render();
	}

	setCursor(id: string | null): void {
		this.cursor = id ?? null;
		this.render();
	}

	// ── Layout ───────────────────────────────────────────────────────────────

	private layout(ops: EvolutionOperation[]): TreeLayout {
		if (!ops.length) { return { nodes: [], edges: [], nodeMap: {} }; }

		const byId: Record<string, EvolutionOperation> = {};
		ops.forEach((op) => { byId[op.id] = op; });

		const children: Record<string, string[]> = {};
		const roots: string[] = [];
		ops.forEach((op) => {
			const pid = op.predecessor && op.predecessor !== 'null' ? op.predecessor : null;
			if (pid && byId[pid]) {
				if (!children[pid]) { children[pid] = []; }
				children[pid].push(op.id);
			} else {
				roots.push(op.id);
			}
		});

		const depth: Record<string, number> = {};
		const queue = roots.slice();
		roots.forEach((r) => { depth[r] = 0; });
		while (queue.length) {
			const cur = queue.shift()!;
			(children[cur] ?? []).forEach((c) => {
				depth[c] = (depth[cur] ?? 0) + 1;
				queue.push(c);
			});
		}

		const x: Record<string, number> = {};
		let leafCounter = 0;
		const assignX = (id: string): void => {
			const ch = children[id] ?? [];
			if (ch.length === 0) {
				x[id] = leafCounter++ * (NODE_R * 2 + MIN_SEP);
				return;
			}
			ch.forEach(assignX);
			const xs = ch.map((c) => x[c]);
			x[id] = (Math.min(...xs) + Math.max(...xs)) / 2;
		};
		roots.forEach(assignX);

		const nodes: TreeNode[] = ops.map((op) => ({
			id:          op.id,
			type:        op.type,
			date:        op.date,
			result:      op.result || null,
			predecessor: op.predecessor || null,
			rule:        op.rule || null,
			x:           x[op.id] ?? 0,
			y:           (depth[op.id] ?? 0) * LEVEL_H,
		}));

		const nodeMap: Record<string, TreeNode> = {};
		nodes.forEach((n) => { nodeMap[n.id] = n; });

		const edges: TreeEdge[] = [];
		nodes.forEach((n) => {
			if (n.predecessor && nodeMap[n.predecessor]) {
				edges.push({ from: nodeMap[n.predecessor], to: n });
			}
		});

		return { nodes, edges, nodeMap };
	}

	// ── Rendering ─────────────────────────────────────────────────────────────

	private centerOnCursor(): void {
		const lyt = this.layout(this.ops);
		if (!lyt.nodes.length) { this.offX = 0; this.offY = 0; return; }

		const wrapW  = this.wrapEl.clientWidth  || 300;
		const wrapH  = this.wrapEl.clientHeight || 220;
		const pad    = NODE_R * 4;
		let target   = lyt.nodes.find((n) => n.id === this.cursor) ?? lyt.nodes[0];
		const allX   = lyt.nodes.map((n) => n.x);
		const minX   = Math.min(...allX);
		const maxX   = Math.max(...allX);
		const treeW  = maxX - minX || 1;
		const centreX = (wrapW - treeW) / 2 - minX;

		this.offX = wrapW  / 2 - target.x - centreX;
		this.offY = wrapH  / 2 - target.y - pad;
	}

	private render(): void {
		(this.svgEl as unknown as Element).innerHTML = '';
		(this.tooltipEl as HTMLElement).style.display = 'none';
		this.hideContextMenu();
		if (!this.ops.length) { return; }

		const lyt   = this.layout(this.ops);
		const nodes = lyt.nodes;
		const edges = lyt.edges;

		const allX  = nodes.map((n) => n.x);
		const allY  = nodes.map((n) => n.y);
		const minX  = Math.min(...allX);
		const maxX  = Math.max(...allX);
		const minY  = Math.min(...allY);
		const maxY  = Math.max(...allY);
		const treeW = (maxX - minX) || 1;
		const treeH = (maxY - minY) || 1;
		const wrapW = this.wrapEl.clientWidth  || 300;
		const wrapH = this.wrapEl.clientHeight || 220;
		const pad   = NODE_R * 4;
		const svgW  = Math.max(wrapW, (treeW + pad * 2) * this.scale);
		const svgH  = Math.max(wrapH, (treeH + pad * 2) * this.scale + NODE_R * 2);

		this.svgEl.setAttribute('width',  String(svgW));
		this.svgEl.setAttribute('height', String(svgH));

		const centreX = (wrapW - treeW * this.scale) / 2 - minX * this.scale;
		const g = this.svgMake('g', {
			transform: `translate(${this.offX + centreX},${this.offY + pad}) scale(${this.scale})`,
		});

		edges.forEach((e) => {
			const x1 = e.from.x, y1 = e.from.y + NODE_R;
			const x2 = e.to.x,   y2 = e.to.y   - NODE_R;
			const cy = (y1 + y2) / 2;
			g.appendChild(this.svgMake('path', {
				class: 't-edge',
				d: `M${x1},${y1} C${x1},${cy} ${x2},${cy} ${x2},${y2}`,
			}));
		});

		nodes.forEach((n) => {
			const isCursor   = n.id === this.cursor;
			const hasResult  = !!n.result;
			const stateClass = isCursor     ? 'state-cursor'
				: hasResult   ? 'state-has-checkpoint'
				: 'state-none';

			const nodeG  = this.svgMake('g', {});
			nodeG.appendChild(this.svgMake('circle', {
				class: `t-node-bg ${stateClass}`, cx: String(n.x), cy: String(n.y), r: String(NODE_R),
			}));
			const shortLabel = n.type === 'original' ? '⬤' : n.type === 'rule' ? '▶' : '✎';
			const lbl = this.svgMake('text', { class: 't-label', x: String(n.x), y: String(n.y) });
			lbl.textContent = shortLabel;
			nodeG.appendChild(lbl);

			const hit = this.svgMake('circle', {
				class: 't-node-hit', cx: String(n.x), cy: String(n.y), r: String(NODE_R + 4),
			});
			if (hasResult) {
				(hit as SVGElement).style.cursor = 'pointer';
				hit.addEventListener('click', (evt) => {
					evt.stopPropagation();
					this.vscode.postMessage({ type: 'treeNodeClicked', operationId: n.id, resultPath: n.result! });
				});
				hit.addEventListener('contextmenu', (evt: MouseEvent) => {
					evt.preventDefault();
					evt.stopPropagation();
					const wrapOff = this.wrapEl.getBoundingClientRect();
					const mx = evt.clientX - wrapOff.left;
					const my = evt.clientY - wrapOff.top;
					this.showContextMenu(mx, my, n.id);
				});
			}
			hit.addEventListener('mouseenter', (evt) => {
				const wrapOff = this.wrapEl.getBoundingClientRect();
				const mx = evt.clientX - wrapOff.left;
				const my = evt.clientY - wrapOff.top;
				const lines = [
					`<b>${n.type || 'op'}</b>`,
					n.date   ? new Date(n.date).toLocaleString() : '',
					n.rule   ? `Rule: ${n.rule}` : '',
					n.result ? `📄 ${n.result}`  : '(no checkpoint)',
					isCursor ? '⟵ cursor' : '',
				].filter(Boolean).join('<br>');
				this.tooltipEl.innerHTML      = lines;
				this.tooltipEl.style.display  = 'block';
				this.tooltipEl.style.left     = `${mx + 14}px`;
				this.tooltipEl.style.top      = `${my - 10}px`;
			});
			hit.addEventListener('mouseleave', () => { this.tooltipEl.style.display = 'none'; });

			nodeG.appendChild(hit);
			g.appendChild(nodeG);
		});

		(this.svgEl as unknown as Element).appendChild(g);
	}

	// ── Controls ──────────────────────────────────────────────────────────────

	private initControls(): void {
		document.getElementById('treeBtnZoomIn')!.addEventListener('click', () => {
			this.scale = Math.min(3, this.scale * 1.3);
			this.render();
		});
		document.getElementById('treeBtnZoomOut')!.addEventListener('click', () => {
			this.scale = Math.max(0.2, this.scale / 1.3);
			this.render();
		});
		document.getElementById('treeBtnReset')!.addEventListener('click', () => {
			this.scale = 1;
			this.centerOnCursor();
			this.render();
		});

		this.wrapEl.addEventListener('wheel', (e) => {
			e.preventDefault();
			const factor = e.deltaY < 0 ? 1.12 : 0.89;
			this.scale   = Math.min(3, Math.max(0.2, this.scale * factor));
			this.render();
		}, { passive: false });

		this.wrapEl.addEventListener('mousedown', (e) => {
			this.dragging   = true;
			this.dragStartX = e.clientX;
			this.dragStartY = e.clientY;
			this.dragOffX   = this.offX;
			this.dragOffY   = this.offY;
		});
		window.addEventListener('mousemove', (e) => {
			if (!this.dragging) { return; }
			this.offX = this.dragOffX + (e.clientX - this.dragStartX);
			this.offY = this.dragOffY + (e.clientY - this.dragStartY);
			this.render();
		});
		window.addEventListener('mouseup', () => { this.dragging = false; });
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private createContextMenu(): HTMLDivElement {
		const el = document.createElement('div');
		el.style.position = 'absolute';
		el.style.display = 'none';
		el.style.zIndex = '20';
		el.style.minWidth = '160px';
		el.style.padding = '6px';
		el.style.borderRadius = '6px';
		el.style.border = '1px solid var(--vscode-menu-border, var(--vscode-widget-border))';
		el.style.background = 'var(--vscode-menu-background, var(--vscode-editorHoverWidget-background, #252526))';
		el.style.color = 'var(--vscode-menu-foreground, var(--vscode-editorHoverWidget-foreground, #ccc))';
		el.style.boxShadow = '0 10px 28px rgba(0,0,0,0.35)';
		// Parent `#tree-canvas-wrap` uses mousedown for panning and also hides this menu on
		// mousedown. Without stopping propagation, that runs before the Delete button's click
		// and clears `dataset.operationId`, so delete appeared to do nothing.
		el.addEventListener('mousedown', (e) => e.stopPropagation());

		const btn = document.createElement('button');
		btn.type = 'button';
		btn.textContent = 'Delete';
		btn.style.width = '100%';
		btn.style.textAlign = 'left';
		btn.style.padding = '6px 8px';
		btn.style.border = 'none';
		btn.style.borderRadius = '4px';
		btn.style.background = 'transparent';
		btn.style.color = 'inherit';
		btn.style.cursor = 'pointer';
		btn.addEventListener('mouseenter', () => { btn.style.background = 'var(--vscode-toolbar-hoverBackground, rgba(255,255,255,0.08))'; });
		btn.addEventListener('mouseleave', () => { btn.style.background = 'transparent'; });
		btn.addEventListener('click', (e) => {
			e.stopPropagation();
			const operationId = el.dataset['operationId'];
			this.hideContextMenu();
			if (!operationId) { return; }
			this.vscode.postMessage({ type: 'deleteCheckpoint', operationId });
		});
		el.appendChild(btn);

		// Hide on any click outside the menu.
		window.addEventListener('click', () => this.hideContextMenu());
		window.addEventListener('blur',  () => this.hideContextMenu());
		this.wrapEl.addEventListener('scroll', () => this.hideContextMenu(), { passive: true });
		this.wrapEl.addEventListener('mousedown', () => this.hideContextMenu());

		this.wrapEl.appendChild(el);
		return el;
	}

	private showContextMenu(x: number, y: number, operationId: string): void {
		this.ctxMenuEl.dataset['operationId'] = operationId;
		this.ctxMenuEl.style.left = `${Math.max(0, x)}px`;
		this.ctxMenuEl.style.top  = `${Math.max(0, y)}px`;
		this.ctxMenuEl.style.display = 'block';
	}

	private hideContextMenu(): void {
		this.ctxMenuEl.style.display = 'none';
		delete this.ctxMenuEl.dataset['operationId'];
	}

	private svgMake(tag: string, attrs: Record<string, string>): SVGElement {
		const el = document.createElementNS('http://www.w3.org/2000/svg', tag) as SVGElement;
		Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
		return el;
	}
}
