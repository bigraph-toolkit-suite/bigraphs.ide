/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.glsp.example.bigraph;

import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.server.launch.DefaultCLIParser;
import org.eclipse.glsp.server.launch.GLSPServerLauncher;
import org.eclipse.glsp.server.launch.SocketGLSPServerLauncher;
import org.eclipse.glsp.server.di.ServerModule;

/**
 * Server launcher for the Bigraph XMI editor.
 * This launcher configures the GLSP server to handle XMI files containing Bigraph models.
 */
public final class BigraphXMIServerLauncher {

    private static final Logger LOGGER = LogManager.getLogger(BigraphXMIServerLauncher.class);

    private BigraphXMIServerLauncher() {
        // Utility class - no instantiation
    }

    public static void main(final String[] args) {
        LOGGER.info("🚀 Starting Bigraph XMI GLSP Server...");

        //DemoBigraphCreator.createAndExportDemoBigraph();
        
        try {
            DefaultCLIParser parser = new DefaultCLIParser(args, "bigraph-xmi-glsp-server");
            
            if (parser.isHelp()) {
                parser.printHelp();
                return;
            }

            ServerModule serverModule = new ServerModule().configureDiagramModule(new BigraphXMIDiagramModule());
            GLSPServerLauncher launcher = new SocketGLSPServerLauncher(serverModule);
            
            // Configure server parameters
            int port = parser.parsePort();
            String host = parser.parseHostname();
            
            LOGGER.info("🌐 Server configuration - Host: {}, Port: {}", host, port);
            LOGGER.info("📁 Supported file extensions: .xmi");
            LOGGER.info("🎯 Diagram type: bigraph-xmi");
            
            launcher.start(host, port);
            
        } catch (ParseException e) {
            LOGGER.error("❌ Failed to parse command line arguments", e);
            System.exit(1);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start Bigraph XMI GLSP Server", e);
            System.exit(1);
        }
    }
}

