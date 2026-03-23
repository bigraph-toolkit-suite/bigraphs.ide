import * as http from 'http';
import * as fs from 'fs';
import * as path from 'path';
import { BigraphFacade } from './bigraph-facade';

export class McpApiServer {
    private server: http.Server | null = null;
    private port: number = 0;
    private portFilePath: string;

    constructor(
        private facade: BigraphFacade,
        private extensionStoragePath: string
    ) {
        this.portFilePath = path.join(extensionStoragePath, 'mcp-api-port');
    }

    async start(): Promise<number> {
        return new Promise((resolve, reject) => {
            this.server = http.createServer((req, res) => {
                this.handleRequest(req, res);
            });

            this.server.listen(0, '127.0.0.1', () => {
                const address = this.server!.address();
                if (address && typeof address === 'object') {
                    this.port = address.port;

                    if (!fs.existsSync(this.extensionStoragePath)) {
                        fs.mkdirSync(this.extensionStoragePath, { recursive: true });
                    }
                    fs.writeFileSync(this.portFilePath, String(this.port));

                    console.log(`[bigraphide] MCP API server listening on 127.0.0.1:${this.port}`);
                    resolve(this.port);
                } else {
                    reject(new Error('Failed to get server address'));
                }
            });

            this.server.on('error', reject);
        });
    }

    stop(): void {
        if (this.server) {
            this.server.close();
            this.server = null;
        }
        try {
            if (fs.existsSync(this.portFilePath)) {
                fs.unlinkSync(this.portFilePath);
            }
        } catch { /* ignore */ }
    }

    getPort(): number {
        return this.port;
    }

    private async handleRequest(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
        const remoteAddress = req.socket.remoteAddress;
        if (remoteAddress !== '127.0.0.1' && remoteAddress !== '::1' && remoteAddress !== '::ffff:127.0.0.1') {
            res.writeHead(403);
            res.end('Forbidden');
            return;
        }

        if (req.method !== 'POST') {
            res.writeHead(405);
            res.end('Method not allowed');
            return;
        }

        let method = '<unknown>';
        try {
            const body = await this.readBody(req);
            const parsed = JSON.parse(body) as { method?: string; params?: Record<string, unknown> };
            method = parsed.method ?? '<unknown>';
            const params = parsed.params ?? {};
            console.log(`[bigraphide] MCP API request: ${method}`);
            const result = await this.dispatch(method, params);
            console.log(`[bigraphide] MCP API response: ${method} OK`);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(result));
        } catch (e: unknown) {
            const message = e instanceof Error ? e.message : String(e);
            console.warn(`[bigraphide] MCP API response: ${method} ERROR: ${message}`);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: message }));
        }
    }

    private readBody(req: http.IncomingMessage): Promise<string> {
        return new Promise((resolve, reject) => {
            let body = '';
            req.on('data', (chunk: Buffer) => { body += chunk.toString(); });
            req.on('end', () => resolve(body));
            req.on('error', reject);
        });
    }

    private async dispatch(method: string, params: Record<string, unknown>): Promise<unknown> {
        const fp = params.filePath as string | undefined;

        switch (method) {
            case 'getContext':
                return this.facade.getContext();
            case 'getSummary':
                return this.facade.getSummary(fp);
            case 'getRoots':
                return this.facade.getRoots(fp);
            case 'getChildren':
                return this.facade.getChildren(params.nodeId as string, fp);
            case 'getNodeInfo':
                return this.facade.getNodeInfo(params.nodeId as string, fp);
            case 'findByControl':
                return this.facade.findByControl(params.control as string, fp);
            case 'getSignature':
                return this.facade.getSignature(fp);
            case 'getLinks':
                return this.facade.getLinks(fp);
            case 'getNeighbors':
                return this.facade.getNeighbors(params.nodeId as string, fp);
            case 'addNode':
                return this.facade.addNode(params.parentId as string, params.controlName as string, fp);
            case 'addSite':
                return this.facade.addSite(fp);
            case 'addInnerName':
                return this.facade.addInnerName(fp);
            case 'addOuterName':
                return this.facade.addOuterName(fp);
            case 'addEdge':
                return this.facade.addEdge(fp);
            case 'deleteElement':
                return this.facade.deleteElement(params.elementId as string, fp);
            case 'createEdge':
                return this.facade.createEdge(params.sourceId as string, params.targetId as string, fp);
            case 'autoLayout':
                return this.facade.autoLayout(params.algorithm as string | undefined, fp);
            default:
                throw new Error(`Unknown method: ${method}`);
        }
    }
}
