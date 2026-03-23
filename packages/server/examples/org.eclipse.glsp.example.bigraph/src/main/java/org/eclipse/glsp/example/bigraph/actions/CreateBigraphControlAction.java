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

public class CreateBigraphControlAction extends Action {
    public static final String KIND = "bigraph.createControl";
    
    private String name;
    private int arity;
    private String status;

    public CreateBigraphControlAction() {
        super(KIND);
    }

    public CreateBigraphControlAction(String name, int arity, String status) {
        super(KIND);
        this.name = name;
        this.arity = arity;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getArity() {
        return arity;
    }

    public void setArity(int arity) {
        this.arity = arity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
