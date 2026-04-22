/**
 * Standalone MCP stdio server entry point.
 * Spawned by Cursor via .cursor/mcp.json. Proxies tool calls to the BigraphIDE
 * HTTP API running in the extension host. Must NOT import vscode or extension code.
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import * as http from 'http';
import * as fs from 'fs';

// ─── HTTP client to extension host API ───

const apiPort = parseInt(process.env.BIGRAPH_MCP_API_PORT ?? '', 10);
const portFilePath = process.env.BIGRAPH_MCP_PORT_FILE ?? '';

function resolvePort(): number {
    if (apiPort && !isNaN(apiPort)) {
        return apiPort;
    }
    if (portFilePath && fs.existsSync(portFilePath)) {
        const port = parseInt(fs.readFileSync(portFilePath, 'utf-8').trim(), 10);
        if (!isNaN(port)) {
            return port;
        }
    }
    throw new Error(
        'Cannot connect to BigraphIDE extension. ' +
        'Make sure the BigraphIDE extension is running with a bigraph file open. ' +
        'Set BIGRAPH_MCP_API_PORT or BIGRAPH_MCP_PORT_FILE environment variable.'
    );
}

const API_REQUEST_TIMEOUT_MS = 25_000;

async function callApi(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
    const port = resolvePort();
    const body = JSON.stringify({ method, params });

    return new Promise((resolve, reject) => {
        const req = http.request(
            {
                hostname: '127.0.0.1',
                port,
                path: '/',
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(body),
                },
            },
            (res) => {
                let data = '';
                res.on('data', (chunk: Buffer) => { data += chunk.toString(); });
                res.on('end', () => {
                    try {
                        const parsed = JSON.parse(data) as { error?: string };
                        if (parsed.error) {
                            reject(new Error(parsed.error));
                        } else {
                            resolve(parsed);
                        }
                    } catch {
                        reject(new Error(`Invalid API response: ${data}`));
                    }
                });
            }
        );
        req.setTimeout(API_REQUEST_TIMEOUT_MS, () => {
            req.destroy();
            reject(new Error(
                `BigraphIDE API did not respond within ${API_REQUEST_TIMEOUT_MS / 1000}s. ` +
                'Check that a bigraph diagram tab is open and the GLSP server is running.'
            ));
        });
        req.on('error', (e) => {
            reject(new Error(
                `Cannot reach BigraphIDE extension API on port ${port}: ${e.message}. ` +
                'Make sure the extension is running.'
            ));
        });
        req.write(body);
        req.end();
    });
}

function textContent(data: unknown): { content: Array<{ type: 'text'; text: string }> } {
    return {
        content: [{ type: 'text' as const, text: JSON.stringify(data, null, 2) }],
    };
}

const optionalFileSchema = {
    filePath: z.string().optional().describe(
        'Optional. Absolute path to a specific bigraph file. If omitted, uses the currently active editor tab.'
    ),
};

type OptionalFile = { filePath?: string };
type NodeIdAndFile = { nodeId: string; filePath?: string };
type ControlAndFile = { control: string; filePath?: string };
type ParentControlAndFile = { parentId: string; controlName: string; filePath?: string };
type ElementIdAndFile = { elementId: string; filePath?: string };
type SourceTargetAndFile = { sourceId: string; targetId: string; filePath?: string };
type AlgorithmAndFile = { algorithm?: string; filePath?: string };
type AddControlParams = { name: string; arity: number; status?: string; filePath?: string };

// ─── MCP server ───

const server = new McpServer({
    name: 'bigraph-editor',
    version: '1.0.0',
});

// META
server.registerTool(
    'bigraph_context',
    {
        title: 'Bigraph Context',
        description: 'Shows which bigraph file is currently active and lists all open bigraph editor tabs. Call this first when unsure which file you are working on.',
        inputSchema: z.object({}),
    },
    async () => textContent(await callApi('getContext'))
);

// READS
server.registerTool(
    'bigraph_summary',
    {
        title: 'Bigraph Summary',
        description: 'Returns a quick statistical overview of a bigraph: total nodes, roots, sites, hyperedges, edges, list of control types, and maximum nesting depth.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('getSummary', { filePath }))
);

server.registerTool(
    'bigraph_getRoots',
    {
        title: 'Bigraph Get Roots',
        description: 'Returns all root regions of the bigraph (top-level containers in the place graph). Each root has an id, label, and child count. Use this as entry point to explore top-down.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('getRoots', { filePath }))
);

server.registerTool(
    'bigraph_getChildren',
    {
        title: 'Bigraph Get Children',
        description: 'Returns the direct children of a node in the place graph. Use this to lazily drill down into the bigraph hierarchy without loading the entire tree.',
        inputSchema: z.object({
            nodeId: z.string().describe('The GModel ID of the parent node.'),
            ...optionalFileSchema,
        }),
    },
    async ({ nodeId, filePath }: NodeIdAndFile) => textContent(await callApi('getChildren', { nodeId, filePath }))
);

server.registerTool(
    'bigraph_getNodeInfo',
    {
        title: 'Bigraph Get Node Info',
        description: 'Returns detailed information about a single node: type, label, controlType, arity, ports with link names, parent, children, connected edges, and ancestor path.',
        inputSchema: z.object({
            nodeId: z.string().describe('The GModel ID of the node.'),
            ...optionalFileSchema,
        }),
    },
    async ({ nodeId, filePath }: NodeIdAndFile) => textContent(await callApi('getNodeInfo', { nodeId, filePath }))
);

server.registerTool(
    'bigraph_findByControl',
    {
        title: 'Bigraph Find By Control',
        description: 'Finds all nodes with a given control type (e.g. "Sensor", "Room"). Returns matching nodes with id, label, and child count.',
        inputSchema: z.object({
            control: z.string().describe('The control type name to search for.'),
            ...optionalFileSchema,
        }),
    },
    async ({ control, filePath }: ControlAndFile) => textContent(await callApi('findByControl', { control, filePath }))
);

server.registerTool(
    'bigraph_getSignature',
    {
        title: 'Bigraph Get Signature',
        description: 'Returns all available control types with their arity and kind (ACTIVE/PASSIVE/ATOMIC). Use this to know which controlName values are valid when creating nodes.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('getSignature', { filePath }))
);

server.registerTool(
    'bigraph_addControl',
    {
        title: 'Bigraph Add Control',
        description:
            'Adds a new control to the bigraph signature (updates companion signature files and reloads the diagram). ' +
            'After this, bigraph_addNode can use the new controlName. status: ATOMIC (default), ACTIVE, or PASSIVE.',
        inputSchema: z.object({
            name: z.string().describe('Name of the new control (unique in the signature).'),
            arity: z.number().int().min(0).describe('Link arity (number of ports) for this control.'),
            status: z
                .enum(['ATOMIC', 'ACTIVE', 'PASSIVE'])
                .optional()
                .describe('Control kind. Default: ATOMIC.'),
            ...optionalFileSchema,
        }),
    },
    async ({ name, arity, status, filePath }: AddControlParams) =>
        textContent(await callApi('addControl', { name, arity, status, filePath }))
);

server.registerTool(
    'bigraph_getLinks',
    {
        title: 'Bigraph Get Links',
        description: 'Returns all hyperedges (links) in the bigraph with names and connected node IDs. Use this to understand the link graph (connectivity).',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('getLinks', { filePath }))
);

server.registerTool(
    'bigraph_getNeighbors',
    {
        title: 'Bigraph Get Neighbors',
        description: 'Returns all nodes connected to a given node via shared hyperedges in the link graph.',
        inputSchema: z.object({
            nodeId: z.string().describe('The GModel ID of the node.'),
            ...optionalFileSchema,
        }),
    },
    async ({ nodeId, filePath }: NodeIdAndFile) => textContent(await callApi('getNeighbors', { nodeId, filePath }))
);

// WRITES
server.registerTool(
    'bigraph_addNode',
    {
        title: 'Bigraph Add Node',
        description: 'Creates a new bigraph node with a given control type as a child of an existing node, root, or site. The server handles placement and layout.',
        inputSchema: z.object({
            parentId: z.string().describe('GModel ID of the parent (root, node, or site).'),
            controlName: z.string().describe('Control type name (must exist in signature).'),
            ...optionalFileSchema,
        }),
    },
    async ({ parentId, controlName, filePath }: ParentControlAndFile) =>
        textContent(await callApi('addNode', { parentId, controlName, filePath }))
);

server.registerTool(
    'bigraph_addSite',
    {
        title: 'Bigraph Add Site',
        description: 'Creates a bigraph site (hole in the place graph). The site is placed under a root or node; parent is resolved from default position.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('addSite', { filePath }))
);

server.registerTool(
    'bigraph_addInnerName',
    {
        title: 'Bigraph Add Inner Name',
        description: 'Creates an inner name in the link graph (internal interface). Use createEdge to connect it to nodes or outer names.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('addInnerName', { filePath }))
);

server.registerTool(
    'bigraph_addOuterName',
    {
        title: 'Bigraph Add Outer Name',
        description: 'Creates an outer name in the link graph (external interface). Use createEdge to connect nodes or inner names to it.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('addOuterName', { filePath }))
);

server.registerTool(
    'bigraph_addEdge',
    {
        title: 'Bigraph Add Edge',
        description: 'Creates a new hyperedge (link) in the bigraph. Use createEdge to connect nodes or inner/outer names to this edge.',
        inputSchema: z.object(optionalFileSchema),
    },
    async ({ filePath }: OptionalFile) => textContent(await callApi('addEdge', { filePath }))
);

server.registerTool(
    'bigraph_deleteElement',
    {
        title: 'Bigraph Delete Element',
        description: 'Deletes a bigraph element and cleans up associated links. Supports undo.',
        inputSchema: z.object({
            elementId: z.string().describe('GModel ID of the element to delete.'),
            ...optionalFileSchema,
        }),
    },
    async ({ elementId, filePath }: ElementIdAndFile) =>
        textContent(await callApi('deleteElement', { elementId, filePath }))
);

server.registerTool(
    'bigraph_createEdge',
    {
        title: 'Bigraph Create Edge',
        description: 'Creates a link connection between two elements (e.g. node to hyperedge).',
        inputSchema: z.object({
            sourceId: z.string().describe('GModel ID of the source element.'),
            targetId: z.string().describe('GModel ID of the target element.'),
            ...optionalFileSchema,
        }),
    },
    async ({ sourceId, targetId, filePath }: SourceTargetAndFile) =>
        textContent(await callApi('createEdge', { sourceId, targetId, filePath }))
);

server.registerTool(
    'bigraph_autoLayout',
    {
        title: 'Bigraph Auto Layout',
        description: 'Triggers automatic layout using ELK. Algorithms: "layered" (default), "mrtree", "force", "stress".',
        inputSchema: z.object({
            algorithm: z.string().optional().describe('ELK algorithm. Default: "layered".'),
            ...optionalFileSchema,
        }),
    },
    async ({ algorithm, filePath }: AlgorithmAndFile) =>
        textContent(await callApi('autoLayout', { algorithm, filePath }))
);

// ─── Start ───

async function main(): Promise<void> {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    console.error('[bigraph-mcp] MCP stdio server started, proxying to extension API');
}

main().catch((e) => {
    console.error('[bigraph-mcp] Fatal error:', e);
    process.exit(1);
});
