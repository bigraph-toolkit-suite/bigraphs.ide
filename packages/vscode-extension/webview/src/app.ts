import { createBigraphDiagramContainer } from './bigraph-diagram-module';
import { ContainerConfiguration, IActionDispatcher, TYPES } from '@eclipse-glsp/client';
import { GLSPStarter } from '@eclipse-glsp/vscode-integration-webview';
import '@eclipse-glsp/vscode-integration-webview/css/glsp-vscode.css';
import '@vscode/codicons/dist/codicon.css';
import './bigraph-styles.css';
import { Container } from 'inversify';
import { BigraphCustomPalette } from './palette/bigraph-custom-palette';
import { BigraphBridge, VsCodeApi } from './bigraph-bridge';

declare function acquireVsCodeApi(): VsCodeApi;

// acquireVsCodeApi() can only be called ONCE per webview lifetime in VS Code.
// Cursor (Electron fork) may or may not enforce this restriction.
// We call it here first, then replace the global so that downstream consumers
// (GLSPStarter → vscode-messenger) get the same cached instance.
const vscodeApi = acquireVsCodeApi();
const _cachedAcquire = (): VsCodeApi => vscodeApi;
try { Object.defineProperty(window, 'acquireVsCodeApi', { value: _cachedAcquire, writable: true, configurable: true }); } catch { /* noop */ }
try { Object.defineProperty(globalThis, 'acquireVsCodeApi', { value: _cachedAcquire, writable: true, configurable: true }); } catch { /* noop */ }

class BigraphGLSPStarter extends GLSPStarter {
    public container?: Container;
    
    createContainer(...containerConfiguration: ContainerConfiguration): Container {
        this.container = createBigraphDiagramContainer(...containerConfiguration);
        return this.container;
    }
}

export function launch(): void {
    const starter = new BigraphGLSPStarter();
    const customPalette = new BigraphCustomPalette();
    // Wire GLSP once DI container is ready.
    const wireUpGlsp = (): void => {
        if (starter.container) {
            try {
                const dispatcher = starter.container.get<IActionDispatcher>(TYPES.IActionDispatcher);
                customPalette.setDispatcher(dispatcher);

                console.log('[BigraphBridge] Initializing BigraphBridge...');
                new BigraphBridge(starter.container, vscodeApi);
                console.log('[BigraphBridge] BigraphBridge initialized');
            } catch (err) {
                console.error('[BigraphBridge] Failed to initialize:', err);
            }
        } else {
            setTimeout(wireUpGlsp, 200);
        }
    };
    setTimeout(wireUpGlsp, 200);

    // ── Palette actions (existing) ───────────────────────────────────
    window.addEventListener('bigraph-action', (e: any) => {
        if (starter.container) {
            const dispatcher = starter.container.get<IActionDispatcher>(TYPES.IActionDispatcher);
            dispatcher.dispatch(e.detail);
        }
    });
}
