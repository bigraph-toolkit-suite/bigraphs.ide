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

package org.eclipse.glsp.example.bigraph.actions;

import org.eclipse.glsp.server.actions.Action;

/**
 * Action sent from the frontend when a file is dropped from the VS Code file explorer
 * into the GLSP canvas.
 */
public class FileDroppedAction extends Action {
    public static final String KIND = "bigraph.fileDropped";
    
    private String filePath;
    private Position position;
    
    public FileDroppedAction() {
        super(KIND);
    }
    
    public FileDroppedAction(String filePath, Position position) {
        super(KIND);
        this.filePath = filePath;
        this.position = position;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public Position getPosition() {
        return position;
    }
    
    public void setPosition(Position position) {
        this.position = position;
    }
    
    /**
     * Represents the drop position in canvas coordinates
     */
    public static class Position {
        private double x;
        private double y;
        
        public Position() {
        }
        
        public Position(double x, double y) {
            this.x = x;
            this.y = y;
        }
        
        public double getX() {
            return x;
        }
        
        public void setX(double x) {
            this.x = x;
        }
        
        public double getY() {
            return y;
        }
        
        public void setY(double y) {
            this.y = y;
        }
    }
}
