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

package org.eclipse.glsp.example.bigraph.meta;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;

public class BigraphMetaIO {

    private final EPackage metaPackage;
    private final EClass metaInfoClass;
    private final EClass controlPropertyClass;
    private final EClass pointEntryClass;
    private final EClass controlEntryClass;

    public BigraphMetaIO() {
        EcoreFactory factory = EcoreFactory.eINSTANCE;

        // Define MetaPackage
        metaPackage = factory.createEPackage();
        metaPackage.setName("bigraphMeta");
        metaPackage.setNsPrefix("bm");
        metaPackage.setNsURI("http://org.eclipse.glsp/bigraph/meta");

        // Define ControlProperty
        controlPropertyClass = factory.createEClass();
        controlPropertyClass.setName("ControlProperty");
        addAttribute(controlPropertyClass, "id", EcorePackage.Literals.ESTRING);
        addAttribute(controlPropertyClass, "icon", EcorePackage.Literals.ESTRING);
        addAttribute(controlPropertyClass, "color", EcorePackage.Literals.ESTRING);
        metaPackage.getEClassifiers().add(controlPropertyClass);

        // Define generic PointEntry for maps
        pointEntryClass = factory.createEClass();
        pointEntryClass.setName("PointEntry");
        addAttribute(pointEntryClass, "key", EcorePackage.Literals.ESTRING);
        addAttribute(pointEntryClass, "x", EcorePackage.Literals.EDOUBLE);
        addAttribute(pointEntryClass, "y", EcorePackage.Literals.EDOUBLE);
        metaPackage.getEClassifiers().add(pointEntryClass);

        // Define ControlEntry for map
        controlEntryClass = factory.createEClass();
        controlEntryClass.setName("ControlEntry");
        addAttribute(controlEntryClass, "key", EcorePackage.Literals.ESTRING);
        addReference(controlEntryClass, "value", controlPropertyClass, true, 1);
        metaPackage.getEClassifiers().add(controlEntryClass);

        // Define BigraphMetaInformation
        metaInfoClass = factory.createEClass();
        metaInfoClass.setName("BigraphMetaInformation");
        addReference(metaInfoClass, "nodePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "nodeRelativePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "innerNamePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "outerNamePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "edgePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "sitePositions", pointEntryClass, false, -1);
        addReference(metaInfoClass, "controlMeta", controlEntryClass, false, -1);
        metaPackage.getEClassifiers().add(metaInfoClass);
    }

    private void addAttribute(EClass eClass, String name, org.eclipse.emf.ecore.EDataType type) {
        EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName(name);
        attr.setEType(type);
        // EMF: add through the general structural-features collection.
        eClass.getEStructuralFeatures().add(attr);
    }

    private void addReference(EClass eClass, String name, EClass type, boolean containment, int upperBound) {
        EReference ref = EcoreFactory.eINSTANCE.createEReference();
        ref.setName(name);
        ref.setEType(type);
        ref.setUpperBound(upperBound);
        ref.setContainment(containment);
        if (!containment) {
            ref.setContainment(true); // All are nested in this simple model
        }
        // EMF: add through the general structural-features collection.
        eClass.getEStructuralFeatures().add(ref);
    }

    public void save(BigraphMetaInformation info, String path) throws IOException {
        ResourceSet resSet = new ResourceSetImpl();
        resSet.getPackageRegistry().put(metaPackage.getNsURI(), metaPackage);
        resSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bigraph-meta", new XMIResourceFactoryImpl());
        
        Resource resource = resSet.createResource(URI.createFileURI(path));
        
        EObject root = metaPackage.getEFactoryInstance().create(metaInfoClass);
        
        fillPointMap(root, "nodePositions", info.getNodePositions());
        fillPointMap(root, "nodeRelativePositions", info.getNodeRelativePositions());
        fillPointMap(root, "innerNamePositions", info.getInnerNamePositions());
        fillPointMap(root, "outerNamePositions", info.getOuterNamePositions());
        fillPointMap(root, "edgePositions", info.getEdgePositions());
        fillPointMap(root, "sitePositions", info.getSitePositions());
        
        fillControlMap(root, info.getControlMeta());
        
        resource.getContents().add(root);
        resource.save(Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private void fillPointMap(EObject root, String featureName, Map<String, GPoint> sourceMap) {
        EReference ref = (EReference) metaInfoClass.getEStructuralFeature(featureName);
        java.util.List<EObject> list = (java.util.List<EObject>) root.eGet(ref);
        for (Map.Entry<String, GPoint> entry : sourceMap.entrySet()) {
            EObject item = metaPackage.getEFactoryInstance().create(pointEntryClass);
            item.eSet(pointEntryClass.getEStructuralFeature("key"), entry.getKey());
            item.eSet(pointEntryClass.getEStructuralFeature("x"), entry.getValue().getX());
            item.eSet(pointEntryClass.getEStructuralFeature("y"), entry.getValue().getY());
            list.add(item);
        }
    }

    @SuppressWarnings("unchecked")
    private void fillControlMap(EObject root, Map<String, ControlProperty> sourceMap) {
        EReference ref = (EReference) metaInfoClass.getEStructuralFeature("controlMeta");
        java.util.List<EObject> list = (java.util.List<EObject>) root.eGet(ref);
        for (Map.Entry<String, ControlProperty> entry : sourceMap.entrySet()) {
            EObject controlItem = metaPackage.getEFactoryInstance().create(controlEntryClass);
            controlItem.eSet(controlEntryClass.getEStructuralFeature("key"), entry.getKey());
            
            EObject propItem = metaPackage.getEFactoryInstance().create(controlPropertyClass);
            propItem.eSet(controlPropertyClass.getEStructuralFeature("id"), entry.getValue().getId());
            propItem.eSet(controlPropertyClass.getEStructuralFeature("icon"), entry.getValue().getIcon());
            propItem.eSet(controlPropertyClass.getEStructuralFeature("color"), entry.getValue().getColor());
            
            controlItem.eSet(controlEntryClass.getEStructuralFeature("value"), propItem);
            list.add(controlItem);
        }
    }

    public BigraphMetaInformation load(String path) throws IOException {
        ResourceSet resSet = new ResourceSetImpl();
        resSet.getPackageRegistry().put(metaPackage.getNsURI(), metaPackage);
        resSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bigraph-meta", new XMIResourceFactoryImpl());
        
        Resource resource = resSet.getResource(URI.createFileURI(path), true);
        EObject root = resource.getContents().get(0);
        
        BigraphMetaInformation info = new BigraphMetaInformation();
        
        readPointMap(root, "nodePositions", info.getNodePositions());
        readPointMap(root, "nodeRelativePositions", info.getNodeRelativePositions());
        readPointMap(root, "innerNamePositions", info.getInnerNamePositions());
        readPointMap(root, "outerNamePositions", info.getOuterNamePositions());
        readPointMap(root, "edgePositions", info.getEdgePositions());
        readPointMap(root, "sitePositions", info.getSitePositions());
        
        readControlMap(root, info.getControlMeta());
        
        return info;
    }

    @SuppressWarnings("unchecked")
    private void readPointMap(EObject root, String featureName, Map<String, GPoint> targetMap) {
        EReference ref = (EReference) metaInfoClass.getEStructuralFeature(featureName);
        java.util.List<EObject> list = (java.util.List<EObject>) root.eGet(ref);
        for (EObject item : list) {
            String id = (String) item.eGet(pointEntryClass.getEStructuralFeature("key"));
            double x = (double) item.eGet(pointEntryClass.getEStructuralFeature("x"));
            double y = (double) item.eGet(pointEntryClass.getEStructuralFeature("y"));
            GPoint point = GraphFactory.eINSTANCE.createGPoint();
            point.setX(x);
            point.setY(y);
            targetMap.put(id, point);
        }
    }

    @SuppressWarnings("unchecked")
    private void readControlMap(EObject root, Map<String, ControlProperty> targetMap) {
        EReference ref = (EReference) metaInfoClass.getEStructuralFeature("controlMeta");
        java.util.List<EObject> list = (java.util.List<EObject>) root.eGet(ref);
        for (EObject item : list) {
            String key = (String) item.eGet(controlEntryClass.getEStructuralFeature("key"));
            EObject propItem = (EObject) item.eGet(controlEntryClass.getEStructuralFeature("value"));
            
            ControlProperty prop = new ControlProperty();
            prop.setId((String) propItem.eGet(controlPropertyClass.getEStructuralFeature("id")));
            prop.setIcon((String) propItem.eGet(controlPropertyClass.getEStructuralFeature("icon")));
            prop.setColor((String) propItem.eGet(controlPropertyClass.getEStructuralFeature("color")));
            
            targetMap.put(key, prop);
        }
    }
}
