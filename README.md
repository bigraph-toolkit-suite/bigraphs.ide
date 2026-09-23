# Bigraph IDE

VS Code–based editor for bigraphs: a **VS Code extension** (diagram client + sidebars) talks to an external **Eclipse GLSP Java server** that embeds the [Bigraph Framework](https://github.com/bigraphs/bigraph-framework) (`org.bigraphs.framework`).

Open `bigraph-ide.code-workspace` so the **Server** and **VSCode Extension** folders are both available.

## Quick start (VS Code)

### Install

Once per clone / after dependency updates.

| | Folder | Task |
|---|---|---|
| **Framework** (separate repo) | — | No VS Code task. From your [Bigraph Framework](https://github.com/bigraphs/bigraph-framework) clone: `mvn initialize && mvn clean install -DskipTests` |
| **GLSP plugins** | **Server** | Terminal → Run Task → **Install GLSP Server Plugins** (once per clone / after changes under `packages/server/plugins`). Installs `2.5.0-SNAPSHOT` jars into `~/.m2`. |
| **Frontend** | **VSCode Extension** | Terminal → Run Task → **Install Dependencies** |

### Build and run

Each session, start the backend first. The extension does not embed the Java server.

| | Folder | What to run |
|---|---|---|
| **Backend** | **Server** | Terminal → Run Task → **Build GLSP Bigraph Server**, or **Build and Launch JAR** / F5 → **Build and Debug Bigraph GLSP Server** (build + start). Listens on **52579** (JDWP **5005**). |
| **Frontend** | **VSCode Extension** | F5 → **Run Extension** (runs **build:all** first). |

In the new window, open a **`.xmi`** file → **Open Bigraph Diagram**.

---

## Command line

Same steps without VS Code tasks. Use this if you prefer a terminal, or if a task fails.

### Prerequisites

- **Java 17+** and **Maven 3.6+** (backend)
- **Node.js 18+** and **npm** (frontend; see `packages/vscode-extension/package.json`)
- **Bigraph Framework** **2.4.1** in `~/.m2` (`org.bigraphs.framework:bigraph-core` and related modules; version is set in the example `pom.xml`)

### Repository layout

| Part | Path | Role |
|------|------|------|
| **VS Code extension** | `packages/vscode-extension` | GLSP webview editor, explorers, rewrite/evolution UI, optional MCP tools for agents |
| **GLSP server + Bigraph example** | `packages/server` | Fork of Eclipse GLSP server; Bigraph diagram backend lives in `examples/org.eclipse.glsp.example.bigraph` |

### Bigraph Framework

The framework is **not** in this repo. `mvn initialize` is required once (it installs bundled jars from `etc/libs/`):

```bash
cd /path/to/bigraphs.bigraph-framework
mvn initialize
mvn clean install -DskipTests
```

Use `-DskipTests` if tests fail (see [Troubleshooting](#troubleshooting)).

### GLSP server plugins

The Bigraph backend depends on this fork’s `org.eclipse.glsp.*` artifacts at **`2.5.0-SNAPSHOT`**. Install them into `~/.m2` before packaging the example (this fork does **not** include the upstream Workflow example):

```bash
cd packages/server
mvn clean install -Pm2 -DskipTests
```

Use **`-Pm2`** (Maven jars). A plain `mvn` here activates the default **`p2`** Tycho profile, which is not what the Bigraph JAR uses.

### Backend (GLSP Bigraph server)

Then build the **Bigraph example** module:

```bash
cd packages/server/examples/org.eclipse.glsp.example.bigraph
mvn clean package -DskipTests
java -jar target/org.eclipse.glsp.example.bigraph-2.5.0-SNAPSHOT.jar -p 52579
```

The client expects **port `52579`**. Override with **`GLSP_SERVER_HOST`** / **`GLSP_SERVER_PORT`** when developing (see `packages/vscode-extension/src/glsp/connector.ts`).

Installing plugins is the **Server** task **Install GLSP Server Plugins**. Packaging/running the example is **Build GLSP Bigraph Server** and **Build and Launch JAR**.

### Frontend (VS Code extension)

There is **no Marketplace listing**. After compile, “install” means **F5** (*Run Extension*). The diagram UI lives in `webview/` and needs its own `npm install`; a plain install there currently fails on a `reflect-metadata` / `inversify` peer conflict.

```bash
cd packages/vscode-extension
npm install
cd webview && npm install --legacy-peer-deps && cd ..
npm run compile:all
```

Then F5 → **Run Extension**. `npm run package` only does a production esbuild of the extension host; it does **not** produce a `.vsix`.

Start the Java server first, then open a **`.xmi`** file and use **Open Bigraph Diagram**.

## Troubleshooting

### Bigraph Framework install on macOS

If `mvn clean install` for **bigraphs.bigraph-framework** fails in tests (e.g. missing or environment-specific test resources), you can **skip compiling that test** by renaming it so Maven no longer picks it up:

1. In the framework repo, rename  
   `core/src/test/java/org/bigraphs/framework/core/CompareTest.java`  
   → `CompareTest.java.disabled` (or move it out of `src/test/java`).
2. Run `mvn clean install` again (or use `mvn clean install -DskipTests` to skip all tests).

Restore the original filename when you want the test active again.

## Further reading

- Server / GLSP upstream context: `packages/server/README.md`
- Bigraph example details: `packages/server/examples/org.eclipse.glsp.example.bigraph/README.md`

## License

Licensing is **mixed**; check subtree and file headers before redistribution.

- **Manuel Krombholz** — original work in this repository (notably **`packages/vscode-extension`** and custom sources under **`packages/server/examples/org.eclipse.glsp.example.bigraph`** where marked) is licensed under the **Apache License, Version 2.0** unless a file states otherwise.
- **Eclipse GLSP** — large parts of **`packages/server`** are upstream-derived and licensed under the **Eclipse Public License 2.0**; see `packages/server/LICENSE`.
