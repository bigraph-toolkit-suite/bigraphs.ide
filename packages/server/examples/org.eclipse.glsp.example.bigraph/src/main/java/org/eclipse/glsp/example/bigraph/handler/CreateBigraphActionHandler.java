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

package org.eclipse.glsp.example.bigraph.handler;

import java.io.File;
import java.net.URI;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.example.bigraph.actions.CreateBigraphAction;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

public class CreateBigraphActionHandler extends AbstractActionHandler<CreateBigraphAction> {

    private static final Logger LOGGER = LogManager.getLogger(CreateBigraphActionHandler.class);

    @Override
    public List<Action> executeAction(CreateBigraphAction action) {
        LOGGER.info("🚀 Executing CreateBigraphAction for path: {}", action.getPath());
        
        try {
            File file = toFile(action.getPath());
            String path = file.getAbsolutePath();
            
            if (file.exists()) {
                LOGGER.warn("⚠️ File already exists: {}", path);
                return List.of();
            }
            
            LOGGER.info("🆕 Creating new empty Bigraph at: {}", path);
            BigraphIO.createEmptyBigraphFile(file);
            
            LOGGER.info("✅ Created and saved new Bigraph file");
            return List.of();
            
        } catch (Exception e) {
            LOGGER.error("❌ Failed to create bigraph file", e);
            throw new RuntimeException("Failed to create bigraph file", e);
        }
    }

    private File toFile(final String pathOrUri) {
        if (pathOrUri != null && pathOrUri.startsWith("file:")) {
            return new File(URI.create(pathOrUri));
        }
        return new File(pathOrUri);
    }
}
