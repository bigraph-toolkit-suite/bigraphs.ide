# Eclipse GLSP Server [![Build Status](https://ci.eclipse.org/glsp/job/eclipse-glsp/job/glsp-server/job/master/badge/icon)](https://ci.eclipse.org/glsp/job/eclipse-glsp/job/glsp-server/job/master/)

Contains the code for the Java-based framework to create [GLSP](https://github.com/eclipse-glsp/glsp) server components.

This fork does **not** include the upstream Workflow example (`org.eclipse.glsp.example.workflow`). The Bigraph diagram backend lives in `examples/org.eclipse.glsp.example.bigraph` and is built separately after the plugins are installed.

## Building

The GLSP server bundles are built with Java 17 or higher and maven.
Execute `mvn clean install -Pm2 -DskipTests` (for maven jars in `~/.m2`) or `mvn clean verify -Pp2` (for p2).
The nightly builds are available as maven repository or p2 update site.

### Maven Repositories 

- *Snapshots:* <https://oss.sonatype.org/content/repositories/snapshots/org/eclipse/glsp/>
- *Releases/Release Candiates:* <https://oss.sonatype.org/content/groups/public/org/eclipse/glsp/> (also mirrored to the [maven central repository](https://search.maven.org/search?q=org.eclipse.glsp))

### P2 Update Sites 

- *Snapshots:* <https://download.eclipse.org/glsp/server/p2/nightly/>
- *Release Candidates:* <https://download.eclipse.org/glsp/server/p2/staging/>
- *Releases:* <https://download.eclipse.org/glsp/server/p2/releases/>

All changes on the master branch are deployed automatically to the corresponding snapshot repositories.

## Structure of this repository

- `org.eclipse.glsp.graph`: EMF-based implementation of graphical model that's used for client-server communication
- `org.eclipse.glsp.layout`: Server-based layout using the [Eclipse Layout Kernel](https://www.eclipse.org/elk/) framework (adapted from [Eclipse Sprotty Server](https://www.github.com/eclipse/sprotty-server))
- `org.eclipse.glsp.server`: Generic base implementation for standalone GLSP servers (based on JSON-RPC)
- `org.eclipse.glsp.server.emf`: Reusable implementations if an [EMF](https://www.eclipse.org/modeling/emf/)-based source model is used
- `org.eclipse.glsp.server.websocket`: Extension of the base server implementation for communication over websockets

- `org.eclipse.glsp.example.bigraph`: Bigraph diagram backend (this fork; not part of the plugin reactor)

## Bigraph example

After installing the plugins (`mvn clean install -Pm2 -DskipTests`), package and run the Bigraph server from `examples/org.eclipse.glsp.example.bigraph`. See that module’s README and the repo-root `README.md`.

### Where to find the sources?

In addition to this repository, the related source code can be found here:

- <https://github.com/eclipse-glsp/glsp-client>
- <https://github.com/eclipse-glsp/glsp-theia-integration>
- <https://github.com/eclipse-glsp/glsp-eclipse-integration>
- <https://github.com/eclipse-glsp/glsp-vscode-integration>

## See also

For more information, please visit the [Eclipse GLSP Umbrella repository](https://github.com/eclipse-glsp/glsp) and the [Eclipse GLSP Website](https://www.eclipse.org/glsp/).
If you have questions, please raise them in the [discussions](https://github.com/eclipse-glsp/glsp/discussions) and have a look at our [communication and support options](https://www.eclipse.org/glsp/contact/).
