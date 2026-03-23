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

package org.eclipse.glsp.example.bigraph.model;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.BigraphFileModelManagement;
import org.bigraphs.framework.core.Control;
import org.bigraphs.framework.core.factory.BigraphFactory;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.utils.BigraphUtil;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
public class BigraphIO {

    private static final Logger LOGGER = LogManager.getLogger(BigraphIO.class);

    private BigraphIO() {}

    public static PureBigraphMutable parseBigraphFromFile(final File ofile) {
        final File file = ofile;
        final String filePath = file.getAbsolutePath();

        // Prevent opening signature files as bigraph files
        if (filePath.endsWith(".signature.xmi") || filePath.endsWith(".signature.ecore")) {
            LOGGER.warn("⚠️ Cannot open signature file as bigraph: {}", filePath);
            throw new BigraphLoadException(
                "Cannot open signature files directly. Open the corresponding .xmi bigraph file instead.");
        }

        if (!file.exists()) {
            throw new BigraphLoadException("Bigraph file does not exist: " + filePath);
        }

        if (!file.isFile()) {
            throw new BigraphLoadException("Bigraph path is not a file: " + filePath);
        }

        if (isBlankPlaceholderBigraphFile(file)) {
            try {
                LOGGER.info("Initializing blank placeholder bigraph file as an empty model: {}", filePath);
                createEmptyBigraphFile(file);
            } catch (IOException e) {
                throw new BigraphLoadException("Failed to initialize blank bigraph file: " + filePath, e);
            }
        }

        final List<DynamicControl> controls = loadControls(filePath);
        final EPackage extendedMetaModel = extendMetaModelWithControls(controls);
        final DynamicSignature signature = createSignatureFromControls(controls);

        final List<EObject> bigraphObjects;
        try {
            bigraphObjects = BigraphFileModelManagement.Load.bigraphInstanceModel(extendedMetaModel, filePath);
        } catch (IOException e) {
            throw new BigraphLoadException(
                "Failed to load bigraph from '" + filePath + "'. The file is empty, invalid, or incompatible with its signature files.",
                e);
        }

        if (bigraphObjects == null || bigraphObjects.isEmpty()) {
            throw new BigraphLoadException("No bigraph model could be read from: " + filePath);
        }

        try {
            return BigraphUtil.toMutable(bigraphObjects.get(0), signature);
        } catch (RuntimeException e) {
            throw new BigraphLoadException("Loaded bigraph data could not be converted into a mutable model: " + filePath, e);
        }
    }

    /**
     * Writes a PureBigraphMutable to the given file (XMI format).
     */
    public static void writeToFile(PureBigraphMutable bigraph, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            BigraphFileModelManagement.Store.exportAsInstanceModel(bigraph, out);
            LOGGER.info("📄 Wrote bigraph to: {}", file.getAbsolutePath());
        }
    }

    /**
     * Explicit creation path for a new empty bigraph plus companion signature files.
     */
    public static void createEmptyBigraphFile(final File file) throws IOException {
        final DynamicSignature emptySignature = BigraphFactory.pureSignatureBuilder().create();
        final PureBigraph emptyBigraph = createEmptyImmutableBigraph(emptySignature);
        try (FileOutputStream out = new FileOutputStream(file)) {
            BigraphFileModelManagement.Store.exportAsInstanceModel(emptyBigraph, out);
        }
        writeSignatureToFile(emptySignature, file.getAbsolutePath());
    }

    /**
     * Creates an in-memory empty bigraph with a real root and empty signature.
     * Useful as a safe placeholder state after a failed load.
     */
    public static PureBigraphMutable createEmptyBigraph() {
        final DynamicSignature emptySignature = BigraphFactory.pureSignatureBuilder().create();
        final PureBigraph emptyBigraph = createEmptyImmutableBigraph(emptySignature);
        return PureBigraphBuilder
            .create(emptySignature, emptyBigraph.getMetaModel(), emptyBigraph.getInstanceModel())
            .createMutable();
    }

    /**
     * Writes a {@link DynamicSignature} to the two companion signature files derived from
     * {@code xmiFilePath}: {@code .signature.ecore} (metamodel) and {@code .signature.xmi}
     * (instance model).  These files are used by {@link #loadControls} when loading the bigraph.
     *
     * @param signature  the signature to persist
     * @param xmiFilePath  absolute path of the bigraph {@code .xmi} file (used as base name)
     */
    public static void writeSignatureToFile(DynamicSignature signature, String xmiFilePath) throws IOException {
        String signatureEcorePath = xmiFilePath.replace(".xmi", ".signature.ecore");
        String signatureXmiPath   = xmiFilePath.replace(".xmi", ".signature.xmi");

        try (FileOutputStream ecoreOut = new FileOutputStream(signatureEcorePath)) {
            BigraphFileModelManagement.Store.exportAsMetaModel(signature, ecoreOut);
            LOGGER.info("📄 Wrote signature metamodel to: {}", signatureEcorePath);
        }
        try (FileOutputStream xmiOut = new FileOutputStream(signatureXmiPath)) {
            BigraphFileModelManagement.Store.exportAsInstanceModel(signature, xmiOut);
            LOGGER.info("📄 Wrote signature instance to: {}", signatureXmiPath);
        }
    }

    public static boolean hasNoCompanionSignatureFiles(final File xmiFile) {
        return !signatureEcoreFileFor(xmiFile).exists() && !signatureXmiFileFor(xmiFile).exists();
    }

    private static List<DynamicControl> loadControls(final String xmiFilePath) {
        final String signatureEcoreFilePath = xmiFilePath.replace(".xmi", ".signature.ecore");
        final String signatureXmiFilePath = xmiFilePath.replace(".xmi", ".signature.xmi");

        final File signatureEcoreFile = new File(signatureEcoreFilePath);
        final File signatureXmiFile = new File(signatureXmiFilePath);

        if (!signatureEcoreFile.exists() && !signatureXmiFile.exists()) {
            LOGGER.info("No companion signature files found for {}. Falling back to an in-memory empty signature.", xmiFilePath);
            return List.of();
        }

        if (!signatureEcoreFile.exists() || !signatureXmiFile.exists()) {
            throw new BigraphLoadException(
                "Missing companion signature files for '" + xmiFilePath + "'. Expected '"
                    + signatureEcoreFile.getName() + "' and '" + signatureXmiFile.getName() + "'.");
        }

        return loadControlsFromSignatureFiles(signatureEcoreFilePath, signatureXmiFilePath);
    }

    private static List<DynamicControl> loadControlsFromSignatureFiles(
            final String signatureEcoreFilePath, final String signatureXmiFilePath) {
        try {
            final List<EObject> signatureObjects = BigraphFileModelManagement.Load.signatureInstanceModel(
                signatureEcoreFilePath,  // Metamodel
                signatureXmiFilePath     // Instance
            );

            if (signatureObjects.isEmpty()) {
                throw new BigraphLoadException(
                    "No signature objects found in '" + signatureXmiFilePath + "'.");
            }

            final EObject signatureEObject = signatureObjects.get(0);
            final DynamicSignature signature = BigraphFactory.createOrGetSignature(signatureEObject);

            return signature.getControls().stream()
                .filter(c -> c instanceof DynamicControl)
                .map(c -> (DynamicControl) c)
                .collect(java.util.stream.Collectors.toList());

        } catch (BigraphLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new BigraphLoadException(
                "Failed to load signature files '" + signatureEcoreFilePath + "' and '" + signatureXmiFilePath + "'.", e);
        }
    }

    private static boolean isBlankPlaceholderBigraphFile(final File xmiFile) {
        return xmiFile.length() == 0L && hasNoCompanionSignatureFiles(xmiFile);
    }

    private static File signatureEcoreFileFor(final File xmiFile) {
        return new File(xmiFile.getAbsolutePath().replace(".xmi", ".signature.ecore"));
    }

    private static File signatureXmiFileFor(final File xmiFile) {
        return new File(xmiFile.getAbsolutePath().replace(".xmi", ".signature.xmi"));
    }
    
    private static EPackage extendMetaModelWithControls(List<DynamicControl> controls) {
        try {

            EPackage metaModel = BigraphFileModelManagement.Load.internalBigraphMetaMetaModel();
        
            EClass bNodeClass = (EClass) metaModel.getEClassifier("BNode");
            if (bNodeClass == null) {
                throw new RuntimeException("BNode class not found in base metamodel");
            }
            
            for (Control control : controls) {
                EClass controlClass = EcoreFactory.eINSTANCE.createEClass();
                controlClass.setName(control.getNamedType().stringValue());
                controlClass.getESuperTypes().add(bNodeClass); // Inherit from BNode
                
                metaModel.getEClassifiers().add(controlClass);
            }
        
            EPackage.Registry.INSTANCE.put(metaModel.getNsURI(), metaModel);

            
            return metaModel;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to extend metamodel", e);
        }
    }

    /**
     * Creates a DynamicSignature from control definitions.
     */
    private static DynamicSignature createSignatureFromControls(List<DynamicControl> controls) {
        try {
            var signatureBuilder = BigraphFactory.pureSignatureBuilder();
            for (Control control : controls) {
                signatureBuilder.add(control.getNamedType().stringValue(), control.getArity().getValue().intValue(), control.getControlKind());
            }
            DynamicSignature signature = signatureBuilder.create();
            LOGGER.info("✅ Created signature from {} controls", controls.size());
            return signature;
        } catch (Exception e) {
            LOGGER.error("❌ Failed to create signature from controls", e);
            return BigraphFactory.pureSignatureBuilder().create();
        }
    }

    private static PureBigraph createEmptyImmutableBigraph(final DynamicSignature signature) {
        return BigraphFactory.pureBuilder(signature)
            .root()
            .create();
    }
}
