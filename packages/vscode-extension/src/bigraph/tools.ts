import * as vscode from 'vscode';
import { BigraphFacade } from './bigraph-facade';

function textResult(data: unknown): vscode.LanguageModelToolResult {
    return new vscode.LanguageModelToolResult([
        new vscode.LanguageModelTextPart(JSON.stringify(data, null, 2))
    ]);
}

export function registerBigraphTools(
    _context: vscode.ExtensionContext,
    facade: BigraphFacade
): vscode.Disposable[] {
    const disposables: vscode.Disposable[] = [];

    disposables.push(vscode.lm.registerTool('bigraph-context', {
        async invoke() {
            return textResult(facade.getContext());
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-summary', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.getSummary(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getRoots', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.getRoots(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getChildren', {
        async invoke(options) {
            const { nodeId, filePath } = (options?.input ?? {}) as { nodeId: string; filePath?: string };
            return textResult(await facade.getChildren(nodeId, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getNodeInfo', {
        async invoke(options) {
            const { nodeId, filePath } = (options?.input ?? {}) as { nodeId: string; filePath?: string };
            return textResult(await facade.getNodeInfo(nodeId, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-findByControl', {
        async invoke(options) {
            const { control, filePath } = (options?.input ?? {}) as { control: string; filePath?: string };
            return textResult(await facade.findByControl(control, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getSignature', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.getSignature(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getLinks', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.getLinks(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-getNeighbors', {
        async invoke(options) {
            const { nodeId, filePath } = (options?.input ?? {}) as { nodeId: string; filePath?: string };
            return textResult(await facade.getNeighbors(nodeId, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-addNode', {
        async invoke(options) {
            const { parentId, controlName, filePath } = (options?.input ?? {}) as { parentId: string; controlName: string; filePath?: string };
            return textResult(await facade.addNode(parentId, controlName, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-addSite', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.addSite(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-addInnerName', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.addInnerName(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-addOuterName', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.addOuterName(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-addEdge', {
        async invoke(options) {
            const { filePath } = (options?.input ?? {}) as { filePath?: string };
            return textResult(await facade.addEdge(filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-deleteElement', {
        async invoke(options) {
            const { elementId, filePath } = (options?.input ?? {}) as { elementId: string; filePath?: string };
            return textResult(await facade.deleteElement(elementId, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-createEdge', {
        async invoke(options) {
            const { sourceId, targetId, filePath } = (options?.input ?? {}) as { sourceId: string; targetId: string; filePath?: string };
            return textResult(await facade.createEdge(sourceId, targetId, filePath));
        }
    }));

    disposables.push(vscode.lm.registerTool('bigraph-autoLayout', {
        async invoke(options) {
            const { algorithm, filePath } = (options?.input ?? {}) as { algorithm?: string; filePath?: string };
            return textResult(await facade.autoLayout(algorithm, filePath));
        }
    }));

    return disposables;
}
