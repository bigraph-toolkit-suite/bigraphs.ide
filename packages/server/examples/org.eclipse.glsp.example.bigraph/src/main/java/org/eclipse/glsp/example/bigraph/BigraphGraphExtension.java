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

import org.eclipse.emf.ecore.EFactory;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.glsp.graph.GraphExtension;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.graph.GraphPackage;

/**
 * GraphExtension for Bigraph diagrams.
 * For now, we use the default graph package since we don't have custom EMF models.
 */
public class BigraphGraphExtension implements GraphExtension {

   @Override
   public EPackage getEPackage() { 
      return GraphPackage.eINSTANCE; 
   }

   @Override
   public EFactory getEFactory() { 
      return GraphFactory.eINSTANCE; 
   }
} 