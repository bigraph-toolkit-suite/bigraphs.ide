import { Action, IActionDispatcher, IActionHandler, TYPES } from '@eclipse-glsp/client';
import { inject, injectable, postConstruct } from 'inversify';
import { acquireVsCodeApi } from '../palette-export';
import { ImportJsonOperation, JsonExportAction } from './popp-actions';

/**
 * Moves POPP JSON documents between the server and the VS Code extension, which owns the file dialogs:
 * an exported document ({@link JsonExportAction}) goes to the extension to be saved, and a file the extension opened
 * for import comes back as a message and goes to the server as an {@link ImportJsonOperation}.
 */
@injectable()
export class PoppJsonTransfer implements IActionHandler {
    @inject(TYPES.IActionDispatcher) protected readonly dispatcher!: IActionDispatcher;

    protected readonly vscode = acquireVsCodeApi();

    @postConstruct()
    protected init(): void {
        window.addEventListener('message', (event: MessageEvent) => {
            const msg = event.data as { type?: string; json?: unknown } | undefined;
            if (msg?.type === 'popp.importJsonFile' && typeof msg.json === 'string') {
                void this.dispatcher.dispatch(ImportJsonOperation.create(msg.json));
            }
        });
    }

    handle(action: Action): void {
        if (action.kind === JsonExportAction.KIND) {
            const { json, fileName } = action as JsonExportAction;
            this.vscode?.postMessage({ type: 'popp.saveJson', json, fileName });
        }
    }
}
