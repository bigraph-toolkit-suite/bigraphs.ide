import { Action, EnableDefaultToolsAction, IActionHandler } from '@eclipse-glsp/client';
import { injectable } from 'inversify';
import { poppToolEvents } from './popp-tool-events';

/**
 * GLSP switches back to the default tools after a creation tool has been used (or on Escape).
 * The palette needs to know, to drop its "active tool" highlight or re-arm the connect tool.
 */
@injectable()
export class PoppToolLifecycle implements IActionHandler {
    handle(action: Action): void {
        if (action.kind === EnableDefaultToolsAction.KIND) {
            poppToolEvents.emitDefaultToolsEnabled();
        }
    }
}