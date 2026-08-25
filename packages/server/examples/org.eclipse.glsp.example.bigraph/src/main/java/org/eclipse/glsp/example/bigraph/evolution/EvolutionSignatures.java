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

package org.eclipse.glsp.example.bigraph.evolution;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.Bigraph;
import org.bigraphs.framework.core.BigraphFileModelManagement;
import org.bigraphs.framework.core.Control;
import org.bigraphs.framework.core.EcoreBigraph;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.impl.signature.DynamicSignatureBuilder;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;

/**
 * Signature helpers for evolution. The framework's {@code mergeSignatures}
 * zips control <em>lists by index</em>, so shared labels can disappear when
 * signatures have different orders or lengths. Evolution unions by name instead
 * and rebinds instances onto one EPackage so matching does not see null controls.
 */
public final class EvolutionSignatures {

    private static final Logger LOGGER = LogManager.getLogger(EvolutionSignatures.class);

    private EvolutionSignatures() {}

    /**
     * Left-precedence union of control names. Earlier signatures win on arity/status
     * when the same label appears more than once.
     */
    public static DynamicSignature unionLeft(final List<DynamicSignature> signatures) {
        final Map<String, DynamicControl> byName = new LinkedHashMap<>();
        DynamicSignature first = null;
        if (signatures != null) {
            for (final DynamicSignature signature : signatures) {
                if (signature == null) {
                    continue;
                }
                if (first == null) {
                    first = signature;
                }
                for (final DynamicControl control : signature.getControls()) {
                    final String name = control.getNamedType().stringValue();
                    final DynamicControl existing = byName.putIfAbsent(name, control);
                    if (existing != null && !existing.getArity().equals(control.getArity())) {
                        LOGGER.warn("Signature conflict for control '{}': keeping arity {} and ignoring arity {}. "
                                + "The same control name is declared with different arities in different files.",
                            name, existing.getArity().getValue(), control.getArity().getValue());
                    }
                }
            }
        }
        if (byName.isEmpty()) {
            return first != null ? first : pureSignatureBuilder().create();
        }
        final DynamicSignatureBuilder builder = pureSignatureBuilder();
        byName.values().forEach(control -> builder.newControl(control.getNamedType(), control.getArity())
            .status(control.getControlKind()).assign());
        return builder.create();
    }

    /**
     * Signature containing only controls that actually appear as nodes.
     * Used when persisting a workspace or checkpoint so unused merge extras
     * (rule-only labels) are not written to companion files.
     */
    public static DynamicSignature usedControls(final PureBigraphMutable bigraph) {
        if (bigraph == null || bigraph.getSignature() == null) {
            return pureSignatureBuilder().create();
        }
        final Set<String> used = new LinkedHashSet<>();
        for (final NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            final DynamicControl control = node.getControl();
            if (control != null && control.getNamedType() != null) {
                used.add(control.getNamedType().stringValue());
            }
        }
        return subset(bigraph.getSignature(), used);
    }

    /**
     * Serializes {@code mutable} and reloads it against a fresh EPackage that
     * contains every control in {@code signature}. Nodes then resolve to the
     * merged signature instead of keeping EClasses from their original package.
     *
     * <p>Rebinding preserves the outer interface: outer names without points
     * are lost in the serialize/reload roundtrip and are re-added afterwards,
     * so a rebound bigraph always has the same outer face as its original.
     */
    public static PureBigraph rebind(final EcoreBigraph<?> bigraph, final DynamicSignature signature)
            throws IOException {
        if (bigraph == null) {
            throw new IOException("Cannot rebind a null bigraph.");
        }
        final Set<String> originalOuterNames = outerNamesOf(bigraph);
        final ByteArrayOutputStream xmiBytes = new ByteArrayOutputStream();
        BigraphFileModelManagement.Store.exportAsInstanceModel(bigraph, xmiBytes);
        final EPackage unifiedMetaModel = createUnifiedMetaModel(signature);
        final List<EObject> reloaded = BigraphFileModelManagement.Load
            .bigraphInstanceModel(unifiedMetaModel, new ByteArrayInputStream(xmiBytes.toByteArray()));
        if (reloaded == null || reloaded.isEmpty()) {
            throw new IOException("Rebind produced no instance model.");
        }
        final PureBigraphMutable rebound = PureBigraphBuilder
            .create(signature, unifiedMetaModel, reloaded.get(0))
            .createMutable();
        for (final String name : originalOuterNames) {
            if (!outerNamesOf(rebound).contains(name)) {
                rebound.addOuterName(name);
            }
        }
        return PureBigraphBuilder
            .create(signature, rebound.getMetaModel(), rebound.getInstanceModel())
            .create();
    }

    private static Set<String> outerNamesOf(final EcoreBigraph<?> bigraph) {
        final Set<String> names = new LinkedHashSet<>();
        if (bigraph instanceof Bigraph<?> typed) {
            typed.getOuterNames().forEach(outer -> names.add(outer.getName()));
        }
        return names;
    }

    static DynamicSignature subset(final DynamicSignature source, final Collection<String> names) {
        final DynamicSignatureBuilder builder = pureSignatureBuilder();
        for (final String name : names) {
            final DynamicControl control = source.getControlByName(name);
            if (control == null) {
                continue;
            }
            builder.newControl(control.getNamedType(), control.getArity())
                .status(control.getControlKind()).assign();
        }
        return builder.create();
    }

    private static EPackage createUnifiedMetaModel(final DynamicSignature signature) throws IOException {
        final EPackage metaModel = BigraphFileModelManagement.Load.internalBigraphMetaMetaModel();
        final EClass bNodeClass = (EClass) metaModel.getEClassifier("BNode");
        if (bNodeClass == null) {
            throw new IOException("BNode class not found in base bigraph meta-model");
        }
        if (signature != null) {
            for (final Control<?, ?> control : signature.getControls()) {
                final String name = control.getNamedType().stringValue();
                if (metaModel.getEClassifier(name) != null) {
                    continue;
                }
                final EClass controlClass = EcoreFactory.eINSTANCE.createEClass();
                controlClass.setName(name);
                controlClass.getESuperTypes().add(bNodeClass);
                metaModel.getEClassifiers().add(controlClass);
            }
        }
        EPackage.Registry.INSTANCE.put(metaModel.getNsURI(), metaModel);
        return metaModel;
    }
}
