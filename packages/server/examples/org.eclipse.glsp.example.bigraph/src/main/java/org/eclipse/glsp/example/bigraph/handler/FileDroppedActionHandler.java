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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.example.bigraph.actions.FileDroppedAction;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

/**
 * Handler for file drop events from the VS Code file explorer.
 * This handler is called when a file is dropped into the GLSP canvas.
 */
public class FileDroppedActionHandler extends AbstractActionHandler<FileDroppedAction> {

    private static final Logger LOGGER = LogManager.getLogger(FileDroppedActionHandler.class);

    @Override
    public List<Action> executeAction(FileDroppedAction action) {
        String filePath = action.getFilePath();
        FileDroppedAction.Position position = action.getPosition();
        
        LOGGER.info("📁 File dropped: {} at position ({}, {})", filePath, 
            position != null ? position.getX() : "?", 
            position != null ? position.getY() : "?");
        
        // Call the handler function with the file path
        // TODO: Implement your file drop handling logic here
        handleFileDropped(filePath, position);
        
        return List.of();
    }
    
    /**
     * Handler function called when a file is dropped from the VS Code file explorer
     * into the GLSP canvas.
     * 
     * Implement this function to handle dropped files according to your needs.
     * 
     * @param filePath The file path of the dropped file
     * @param position The drop position in canvas coordinates (may be null)
     */
    private void handleFileDropped(String filePath, FileDroppedAction.Position position) {
        // TODO: Implement your file drop handling logic here
        // This is where you should add your custom logic to process the dropped file
        // For example:
        // - Read the file contents
        // - Create a new node in the diagram at the drop position
        // - Update the GLSP model
        // - Process the file based on its type/extension
        
        LOGGER.info("🔧 Processing dropped file: {}", filePath);
        
        // Example implementation:
        // try {
        //     File file = new File(filePath);
        //     if (file.exists() && file.isFile()) {
        //         // Read file and create diagram element
        //         // ...
        //     }
        // } catch (Exception e) {
        //     LOGGER.error("Failed to process dropped file: " + filePath, e);
        // }
    }
}
