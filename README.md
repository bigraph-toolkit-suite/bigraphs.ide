# Bigraph IDE

VS Code–based editor for bigraphs: a **VS Code extension** (diagram client + sidebars) talks to an external **Eclipse GLSP Java server** that embeds the [Bigraph Framework](https://github.com/bigraphs/bigraph-framework) (`org.bigraphs.framework`).

## TL;DR

### Install (once per clone / after updates)

1. **Bigraph Framework** (separate repo): `cd <bigraph-framework-repo> && mvn clean install -DskipTests`
2. **GLSP Bigraph server** (this repo): `cd packages/server/examples/org.eclipse.glsp.example.bigraph && mvn clean package -DskipTests`
3. **VS Code extension**: `cd packages/vscode-extension && npm install && npm run compile:all`

### Run (each session)

**Hint:** You can start the **server** and **extension** from VS Code **Run and Debug** (Java + Maven task in `packages/server/.vscode/`, **F5** *Run Extension* for the client).

1. **Server** (from this repo’s root): `cd packages/server/examples/org.eclipse.glsp.example.bigraph && java -jar target/org.eclipse.glsp.example.bigraph-2.5.0-SNAPSHOT.jar -p 52579`
2. **Extension:** open `packages/vscode-extension` (or the workspace) and **F5** — *Run Extension*, or use your packaged VSIX in a normal VS Code/Cursor window
3. Open a **`.xmi`** file → **Open Bigraph Diagram** (server must listen on **52579**)

Details, versions, and options below.

## Repository layout

| Part | Path | Role |
|------|------|------|
| **VS Code extension** | `packages/vscode-extension` | GLSP webview editor, explorers, rewrite/evolution UI, optional MCP tools for agents |
| **GLSP server + Bigraph example** | `packages/server` | Fork of Eclipse GLSP server; Bigraph diagram backend lives in `examples/org.eclipse.glsp.example.bigraph` |

Open `bigraph-ide.code-workspace` in VS Code to load all folders at once.

## Prerequisites

- **Java 17+** and **Maven 3.6+** (server)
- **Node.js 18+** and **npm** (extension; see `packages/vscode-extension/package.json`)
- **Bigraph Framework** artifacts in your local Maven repo — the server depends on `org.bigraphs.framework:bigraph-core` (and related modules) at the version declared in the example `pom.xml` (currently **2.4.0**).

### Install the Bigraph Framework (dependency)

From your clone of the framework (separate repo), install into `~/.m2`:

```bash
cd /path/to/bigraphs.bigraph-framework
mvn clean install -DskipTests
```

(Use `-DskipTests` only if you hit known test-resource issues; otherwise a full `mvn clean install` is fine.)

## Part 1 — GLSP Bigraph server

Build the **Bigraph example** module (not necessarily the whole GLSP reactor unless you want to):

```bash
cd packages/server/examples/org.eclipse.glsp.example.bigraph
mvn clean package -DskipTests
```

Run the fat JAR (version suffix matches your build, e.g. `2.5.0-SNAPSHOT`):

```bash
java -jar target/org.eclipse.glsp.example.bigraph-2.5.0-SNAPSHOT.jar -p 52579
```

The VS Code integration expects the server on **port `52579`** by default. You can point the client at another host/port with environment variables **`GLSP_SERVER_HOST`** / **`GLSP_SERVER_PORT`** when developing (see `packages/vscode-extension/src/glsp/connector.ts`).

**VS Code / Cursor:** from the `Server` folder, the tasks in `packages/server/.vscode/tasks.json` can build and launch the JAR (including a debug listen on **5005**).

More detail: `packages/server/examples/org.eclipse.glsp.example.bigraph/README.md`.

## Part 2 — VS Code extension (Bigraphide)

Install dependencies and build the extension (including the webview bundle):

```bash
cd packages/vscode-extension
npm install
npm run compile:all
```

Run / debug:

- Open `packages/vscode-extension` in VS Code (or use the multi-root workspace).
- **F5** — *Run Extension* (use the launch config in that package if present).

Package for installation:

```bash
cd packages/vscode-extension
npm install
npm run package
# Then install the generated .vsix via VS Code: Extensions → … → Install from VSIX…
```

**Workflow:** start the Java server first, then open a **`.xmi`** file and use **Open Bigraph Diagram** (or the custom diagram editor association).

## Current feature set (high level)

### Diagram editing (GLSP + server)

- **Place graph:** roots/regions, **atomic** and **container** nodes, **sites**, hierarchy and nesting  
- **Link graph:** **hyperedges (links)**, edges between ports/names, **inner** and **outer** names  
- **Palette / tools** for creating and connecting elements; **drag-and-drop** from explorer where supported  
- **Auto-layout** via ELK (e.g. layered, mrtree, force, stress)  
- **Persistence** oriented around **XMI** / model storage on the server side  
- **Compose**, **verify**, **rewrite-rule test**, **evolution** runs, **file-drop** handling (server actions wired from the extension)  
- **Read-only mode** toggle for the diagram session  

### VS Code UI

- **Language** registration for **`.xmi`**  
- **Custom editor:** *Bigraph Diagram Editor* (GLSP)  
- **Activity bar — Bigraph Explorer:** **Bigraphs**, **Rewrite Rules**, **Evolutions**, **Evolution Manager** (webview), **Rewrite Rule Editor** (webview)  
- **Commands** (non-exhaustive): create/open bigraph, test rewrite rule, play evolution, add rule sets/rules, compose with canvas, add to verification, toggle read-only, etc.  
- **Context / explorer** integrations (e.g. open diagram from file explorer for `.xmi`)  

### Agent / MCP tooling (optional)

When using a compatible host (e.g. Cursor with MCP), the extension contributes **language model tools** for inspecting and editing the active bigraph: context, summary, roots/children, node info, signature, links, neighbors, find-by-control, add node/site/names/edges, delete element, auto-layout, etc. (see `languageModelTools` in `packages/vscode-extension/package.json`).

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
