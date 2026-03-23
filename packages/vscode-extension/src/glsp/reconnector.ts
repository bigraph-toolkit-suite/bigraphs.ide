import { SocketGlspVscodeServer } from '@eclipse-glsp/vscode-integration';
import { Deferred, RequestModelAction } from '@eclipse-glsp/protocol';
import * as net from 'net';
import { SocketMessageReader, SocketMessageWriter } from 'vscode-jsonrpc/node';
import { createMessageConnection } from 'vscode-jsonrpc';
import * as vscode from 'vscode';

const RECONNECT_POLL_MS  = 2_000;  // how often to retry the port while disconnected
const RECONNECT_DELAY_MS = 2_000;  // wait after port opens before reconnecting (let backend finish init)

// ---------------------------------------------------------------------------
// ReconnectingSocketGlspVscodeServer
// ---------------------------------------------------------------------------
// Subclass that hooks into the underlying net.Socket so we know immediately
// when the backend closes the connection — without any polling.

export class ReconnectingSocketGlspVscodeServer extends SocketGlspVscodeServer {
    readonly onDisconnectEmitter = new vscode.EventEmitter<void>();
    readonly onDisconnect: vscode.Event<void> = this.onDisconnectEmitter.event;

    protected override createSocketConnection(opts: net.TcpSocketConnectOpts) {
        const socket = new net.Socket();
        const reader = new SocketMessageReader(socket);
        const writer = new SocketMessageWriter(socket);
        const connection = createMessageConnection(reader, writer);

        // Only fire disconnect if we were successfully connected first —
        // avoids spurious disconnect events when the initial connect fails.
        let wasConnected = false;
        socket.once('connect', () => { wasConnected = true; });
        socket.once('close',   () => { if (wasConnected) { this.onDisconnectEmitter.fire(); } });
        socket.once('error',   () => { if (wasConnected) { this.onDisconnectEmitter.fire(); } });

        socket.connect(opts);
        return connection;
    }
}

// ---------------------------------------------------------------------------
// GlspReconnector
// ---------------------------------------------------------------------------
// Watches the server socket. When the backend goes away, shows a status-bar
// indicator and polls the port. When the backend is back, disposes only the
// old *server* wrapper and creates a fresh one — the GlspVscodeConnector and
// all open editor panels are left untouched. The new server's message streams
// are re-wired to the connector via the connector's public `options.server`
// emitters so messages flow again without reloading tabs.

export class GlspReconnector implements vscode.Disposable {
    private statusBar: vscode.StatusBarItem;
    private disposed = false;
    private reconnecting = false;
    private retryTimer: ReturnType<typeof setInterval> | undefined;

    /** The *connector*'s options.server — we keep this reference so we can
     *  patch its emitters when a new server is created. */
    private readonly connectorServerRef: {
        onSendToServerEmitter: vscode.EventEmitter<unknown>;
        onServerSendEmitter:   vscode.EventEmitter<unknown>;
    };

    /** Full connector reference — needed to iterate clientMap on reconnect. */
    private readonly connector: vscode.Disposable;


    private currentServer: ReconnectingSocketGlspVscodeServer;
    private readonly port: number;
    private readonly host: number | string;

    /** Disposable for the current forwarding subscription
     *  (connector.onSendToServer → currentServer.onSendToServerEmitter). */
    private forwardDisposable: vscode.Disposable | undefined;

    constructor(
        initialServer: ReconnectingSocketGlspVscodeServer,
        connector: vscode.Disposable & {
            options: {
                server: {
                    onSendToServerEmitter: vscode.EventEmitter<unknown>;
                    onServerSendEmitter:   vscode.EventEmitter<unknown>;
                }
            }
        },
        port: number,
        host: string,
    ) {
        this.currentServer      = initialServer;
        this.connector          = connector;
        this.connectorServerRef = connector.options.server;
        this.port = port;
        this.host = host;

        this.statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
        this.statusBar.tooltip = 'GLSP backend connection status';
        this.statusBar.show();
        this.setStatus('connected');

        this.listenForDisconnect(initialServer);
    }

    // -----------------------------------------------------------------------

    /** Called when the very first `server.start()` fails (backend not yet up).
     *  Puts the reconnector straight into retry mode. */
    notifyInitialConnectionFailed(): void {
        if (this.disposed || this.reconnecting) { return; }
        this.setStatus('disconnected');
        this.startRetrying();
    }

    private listenForDisconnect(server: ReconnectingSocketGlspVscodeServer): void {
        server.onDisconnect(() => {
            if (this.disposed || this.reconnecting) { return; }
            this.setStatus('disconnected');
            this.startRetrying();
        });
    }

    private setStatus(state: 'connected' | 'disconnected' | 'reconnecting'): void {
        if (state === 'connected') {
            this.statusBar.text            = '$(circle-filled) GLSP';
            this.statusBar.color           = new vscode.ThemeColor('charts.green');
            this.statusBar.backgroundColor = undefined;
        } else if (state === 'reconnecting') {
            this.statusBar.text            = '$(sync~spin) GLSP reconnecting…';
            this.statusBar.color           = new vscode.ThemeColor('charts.yellow');
            this.statusBar.backgroundColor = undefined;
        } else {
            this.statusBar.text            = '$(warning) GLSP disconnected';
            this.statusBar.color           = new vscode.ThemeColor('charts.red');
            this.statusBar.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
        }
    }

    private startRetrying(): void {
        if (this.retryTimer !== undefined) { return; }
        this.retryTimer = setInterval(() => this.tryReconnect(), RECONNECT_POLL_MS);
    }

    private stopRetrying(): void {
        if (this.retryTimer !== undefined) {
            clearInterval(this.retryTimer);
            this.retryTimer = undefined;
        }
    }

    /** Probe the TCP port. On connect we immediately destroy the socket so the
     *  backend only sees a RST — no GLSP handshake, no log spam. */
    private tryReconnect(): void {
        if (this.disposed || this.reconnecting) { return; }

        const sock = new net.Socket();
        sock.setTimeout(1_000);

        sock.on('connect', () => {
            sock.destroy();
            this.stopRetrying();
            this.setStatus('reconnecting');
            setTimeout(() => this.doReconnect(), RECONNECT_DELAY_MS);
        });
        sock.on('error',   () => sock.destroy());
        sock.on('timeout', () => sock.destroy());

        sock.connect(this.port as number, this.host as string);
    }

    private async doReconnect(): Promise<void> {
        if (this.disposed) { return; }
        this.reconnecting = true;

        const serverRef    = this.connectorServerRef as any;
        const oldGlspClient = serverRef._glspClient as any;
        const connectorAny = this.connector as any;

        try {
            // 1. Stop only the GLSP client — do NOT call currentServer.dispose() because
            //    dispose() destroys onSendToServerEmitter/onServerSendEmitter, which the
            //    GlspVscodeConnector still holds and routes all messages through.
            //    We stop the client directly so the socket closes but the emitters survive.
            if (oldGlspClient) {
                try { oldGlspClient.stop(); } catch (_) { /* ignore */ }
                // Mark as Starting so any start() calls during reconnect await instead of failing.
                oldGlspClient.state = 1; // ClientState.Starting
            }
            // Block registerClient() calls (await server.glspClient = await onReady) by
            // replacing readyDeferred with a fresh unresolved one. Resolved in step 6.
            const pendingDeferred = new Deferred<void>();
            serverRef.readyDeferred = pendingDeferred;

            // 2. Create a fresh server with the same options as the original.
            const newServer = new ReconnectingSocketGlspVscodeServer({
                clientId: 'glsp.bigraph',
                clientName: 'bigraph',
                connectionOptions: { port: this.port as number }
            });

            // 3. Re-wire Server → Connector message stream.
            //    The Connector → Server direction goes via the original server's
            //    constructor subscription (onSendToServerEmitter → _glspClient.sendActionMessage).
            //    Patching _glspClient below keeps that path working without duplication.
            this.forwardDisposable?.dispose();
            this.forwardDisposable = newServer.onServerMessage((msg: unknown) => {
                this.connectorServerRef.onServerSendEmitter.fire(msg);
            });

            // 4. Start the new server (GLSP initialize handshake with the backend).
            await newServer.start();
            const newGlspClient = await newServer.glspClient;
            const newConn       = (newGlspClient as any).resolvedConnection;

            // 5. Patch oldGlspClient to be a transparent proxy to the new connection.
            //    Existing webviewEndpoint.initialize() closures captured oldGlspClient —
            //    by making it share the live connection and marking it Running, all
            //    StartRequest / InitializeClientSessionRequest calls go through correctly.
            //    Setting state = Running also unblocks any start() calls that were
            //    waiting on state = Starting (set at the top of doReconnect).
            if (oldGlspClient && newConn) {
                oldGlspClient.resolvedConnection = newConn;
                oldGlspClient.connectionPromise  = Promise.resolve(newConn);
                oldGlspClient._initializeResult  = (newGlspClient as any)._initializeResult;
                oldGlspClient.state              = 2; // ClientState.Running — fires onCurrentStateChanged
            }

            // 6. Update serverRef so future registerClient() calls and
            //    onSendToServerEmitter routing use newGlspClient.
            serverRef._glspClient = newGlspClient;
            // Resolve the pending deferred (anyone who awaited onReady during reconnect
            // will now unblock) and also replace it with a fresh resolved one for
            // anyone who evaluates onReady after this point.
            pendingDeferred.resolve();
            const newDeferred = new Deferred<void>();
            newDeferred.resolve();
            serverRef.readyDeferred = newDeferred;

            // 7. Re-register any panels still in clientMap and request their models.
            for (const client of connectorAny.clientMap.values()) {
                try {
                    await newGlspClient.initializeClientSession({
                        clientSessionId:   client.clientId,
                        diagramType:       client.webviewEndpoint?.diagramIdentifier?.diagramType ?? 'bigraph',
                        clientActionKinds: client.webviewEndpoint?.clientActions ?? [],
                    });
                    connectorAny.options.server.onSendToServerEmitter.fire({
                        clientId: client.clientId,
                        action: RequestModelAction.create({
                            options: { sourceUri: client.document?.uri?.toString() }
                        })
                    });
                } catch (e) {
                    console.warn('[GlspReconnector] Could not re-register client session:', e);
                }
            }

            this.currentServer = newServer;
            this.setStatus('connected');
            this.listenForDisconnect(newServer);

            vscode.window.showInformationMessage('GLSP backend reconnected.');
        } catch (err) {
            console.error('[GlspReconnector] Reconnect failed:', err);
            this.setStatus('disconnected');
            this.startRetrying();
        } finally {
            this.reconnecting = false;
        }
    }

    dispose(): void {
        this.disposed = true;
        this.stopRetrying();
        this.forwardDisposable?.dispose();
        this.statusBar.dispose();
    }
}
