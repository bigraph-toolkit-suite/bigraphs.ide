import { Action, EditLabelAction, IActionDispatcher, IActionHandler, TYPES } from '@eclipse-glsp/client';
import { inject, injectable } from 'inversify';
import { StartDescriptionEditAction } from './popp-actions';

/**
 * Opens the inline editor of a node's description right after the node was created, so the user can type instead
 * of double-clicking first. The server sends the request after the model update with the new node; waiting two
 * frames lets that update render, so the editor is placed over the label's final bounds. The editor selects the
 * placeholder text, so typing replaces it; Escape keeps it.
 */
@injectable()
export class DescriptionEditStarter implements IActionHandler {
    @inject(TYPES.IActionDispatcher) protected readonly dispatcher!: IActionDispatcher;

    handle(action: Action): void {
        const labelId = (action as StartDescriptionEditAction).labelId;
        if (!labelId) {
            return;
        }
        requestAnimationFrame(() => requestAnimationFrame(() =>
            this.dispatcher.dispatch(EditLabelAction.create(labelId))));
    }
}
