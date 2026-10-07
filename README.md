# Bigraph IDE

*Bigraph IDE* is a VS Code–based visual editor for bigraphs. The *VS Code extension* provides the diagram client and sidebars and communicates with an external *Eclipse GLSP Java server* that embeds the [Bigraph Framework](https://github.com/bigraphs/bigraph-framework).


## Quick Start

Open the file `bigraph-ide.code-workspace` in VS Code. 
This loads both the *Server* and *VSCode Extension* folders into the workspace.

If you have unsaved tabs in the current VS Code window, open a new window first (<kbd>CTRL+SHIFT+N</kbd>) to avoid replacing the current workspace.


### Install

Once per clone / after dependency updates.

| Step | Task |
|---|---|
| 1. Install *Bigraph Framework* | Clone the [Bigraph Framework](https://github.com/bigraph-toolkit-suite/bigraphs.bigraph-framework) repository `git clone --branch v2.4.4 https://github.com/bigraph-toolkit-suite/bigraphs.bigraph-framework.git` and run `mvn initialize && mvn clean install -DskipTests`. |
| 2. Install *GLSP Server Plugins* | In VS Code: Terminal → Run Task → *Install GLSP Server Plugins*. Installs `2.5.0-SNAPSHOT` jars into `~/.m2`. |
| 3. Install *Frontend* | In VS Code: Terminal → Run Task → *Install Dependencies*. |

### Build and Run

Each session, start the backend first. The extension does not embed the Java server.

| What to run first | Task |
|---|---|
| 1. *Backend* | Terminal → Run Task → *Build and Launch JAR*. Listens on *52579* (JDWP *5005*). |
| 2. *Frontend* |  Open *Run and Debug* with <kbd>Ctrl+Shift+D</kbd>, select *Run Extension (VSCode Extension)* from the launch configuration dropdown, and press *F5*. This runs *build:all* first. |

## Troubleshooting

### Bigraph Framework install on macOS

If `mvn clean install` for *bigraphs.bigraph-framework* fails in tests (e.g. missing or environment-specific test resources), you can *skip compiling that test* by renaming it so Maven no longer picks it up:

1. In the framework repo, rename  
   `core/src/test/java/org/bigraphs/framework/core/CompareTest.java`  
   → `CompareTest.java.disabled` (or move it out of `src/test/java`).
2. Run `mvn clean install` again (or use `mvn clean install -DskipTests` to skip all tests).

Restore the original filename when you want the test active again.

### `npm: command not found` when running the VS Code installation task

If you encounter an error such as the following when following Step 3 of the installation procedure or Step 2 of the build procedure:

```text
Executing task in folder vscode-extension: npm install && npm install --legacy-peer-deps --prefix webview

/usr/bin/bash: line 1: npm: command not found

The terminal process "/usr/bin/bash '-c', 'npm install && npm install --legacy-peer-deps --prefix webview'" failed to launch (exit code: 127).
```

the VS Code task cannot find the `npm` executable. This commonly occurs when Node.js was installed through `nvm` but the `nvm` environment has not been loaded into the shell used by VS Code.

One solution is to modify the corresponding task in `.vscode/tasks.json` so that `nvm` is loaded before `npm` is executed:
```json
{
    "command": "source ~/.nvm/nvm.sh && nvm use v20.18.1 && npm install && npm install --legacy-peer-deps --prefix webview"
}
```

Node.js v20 or later is required. If you use `nvm`, run `nvm use` with a Node.js version ≥20.

### Tycho requires Maven 3.9.0

If the build fails with an error similar to:

```text
The plugin org.eclipse.tycho:tycho-source-plugin:4.0.8
requires Maven version 3.9.0
```

the installed Tycho version requires a newer Maven version than the one currently installed.

In that case, you have to install a new version of Maven.

But Temporarily, you can do the following. 
If you need to remain on Maven 3.8, use the corresponding Tycho version:
- Maven 3.8.6 or later: use Tycho 3.0.5
- Maven 3.8.4-3.8.5: use Tycho 2.7.5

Therefore, set the Tycho version in the `packages/server/pom.xml`, for example:

```xml
<properties>
    <tycho-version>3.0.5</tycho-version>
</properties>
```

## License

Licensing is *mixed*; check subtree and file headers before redistribution.

- *Manuel Krombholz*: original work in this repository (*`packages/vscode-extension`* and custom sources under *`packages/server/examples/org.eclipse.glsp.example.bigraph`* where marked) is licensed under the *Apache License, Version 2.0* unless a file states otherwise.
- *Eclipse GLSP*: large parts of *`packages/server`* are upstream-derived and licensed under the *Eclipse Public License 2.0*; see `packages/server/LICENSE`.
