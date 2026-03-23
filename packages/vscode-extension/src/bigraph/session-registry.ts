import * as vscode from 'vscode';

export interface BigraphSession {
    clientId: string;
    filePath: string;
    fileName: string;
    panel: vscode.WebviewPanel;
}

export class SessionRegistry {
    private sessions = new Map<string, BigraphSession>();
    private _activeClientId: string | null = null;

    register(session: BigraphSession): void {
        this.sessions.set(session.clientId, session);
    }

    unregister(clientId: string): void {
        this.sessions.delete(clientId);
        if (this._activeClientId === clientId) {
            this._activeClientId = null;
        }
    }

    setActive(clientId: string): void {
        this._activeClientId = clientId;
    }

    getActive(): BigraphSession | null {
        if (!this._activeClientId) {
            return null;
        }
        return this.sessions.get(this._activeClientId) ?? null;
    }

    getByClientId(clientId: string): BigraphSession | null {
        return this.sessions.get(clientId) ?? null;
    }

    getByFilePath(filePath: string): BigraphSession | null {
        for (const session of this.sessions.values()) {
            if (session.filePath === filePath) {
                return session;
            }
        }
        return null;
    }

    getAllSessions(): BigraphSession[] {
        return [...this.sessions.values()];
    }

    hasAnySessions(): boolean {
        return this.sessions.size > 0;
    }
}
