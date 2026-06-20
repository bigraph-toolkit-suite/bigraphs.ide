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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// Bigraph Framework Imports
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.factory.BigraphFactory;
import org.bigraphs.framework.core.exceptions.InvalidArityOfControlException;
import org.bigraphs.framework.core.exceptions.InvalidConnectionException;
import org.bigraphs.framework.core.exceptions.builder.LinkTypeNotExistsException;
import org.bigraphs.framework.core.exceptions.builder.TypeNotExistsException;
import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.visualization.BigraphGraphvizExporter;
import org.bigraphs.framework.core.BigraphFileModelManagement;
import org.bigraphs.framework.core.reactivesystem.BigraphMatch;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.simulation.matching.AbstractBigraphMatcher;
import org.bigraphs.framework.simulation.matching.MatchIterable;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Random;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * Creates a clean demo bigraph using the systematic 4-step approach:
 * 1) Define Controls (node types)
 * 2) Create named node instances 
 * 3) Build place graph structure with single root
 * 4) Build link graph connecting specific instances
 */
public class DemoBigraphCreator {

    private static final Logger LOGGER = LogManager.getLogger(DemoBigraphCreator.class);
    private static final Random RANDOM = new Random();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Generates a random 3-character alphanumeric ID
     * @return A random 3-character string
     */
    private static String generateRandomId() {
        StringBuilder id = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            id.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return id.toString();
    }

    /**
     * Creates and demonstrates a clean bigraph using the systematic 4-step approach
     */
    public static void createAndExportDemoBigraph() {
        try {
            LOGGER.info("🚀 Clean 4-Step Demo Bigraph Creation...");
            
            // Full EMF-based demo (enable in Eclipse IDE):
            
                DynamicSignature signature1 = createSignature1();
                PureBigraph demoBigraph1 = createBigraph1(signature1);
                exportSignatureAndBigraph(signature1, demoBigraph1,"original");     

                DynamicSignature signature2 = createSignature2();
                PureBigraph demoBigraph2 = createBigraph2(signature2);
                exportSignatureAndBigraph(signature2, demoBigraph2,"left");   

                DynamicSignature signature3 = createSignature3();
                PureBigraph demoBigraph3 = createBigraph3(signature3);
                exportSignatureAndBigraph(signature3, demoBigraph3, "right");   
            
        } catch (Exception e) {
            LOGGER.error("❌ Error in clean demo: " + e.getMessage(), e);
        }

        // Export composition examples to playground
        exportCompositionExamples();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Composition Examples
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports three composition example bigraphs to the playground folder:
     *
     *  parallel/workspace.xmi  — Room with a Lamp connected to "power"
     *  parallel/addon.xmi      — Room with a Thermostat connected to "heating"
     *                            (different outer name → no juxtapose conflict)
     *
     *  sequential/workspace.xmi — Building with one site (outer bigraph)
     *  sequential/content.xmi   — Person node, one root (inner bigraph)
     *                             Sequential composition fills the site with the Person.
     */
    public static void exportCompositionExamples() {
        try {
            LOGGER.info("🚀 Exporting composition examples...");

            final String playgroundBase = "./output";

            // ── parallel ──────────────────────────────────────────────────────
            DynamicSignature sigParallelWorkspace = pureSignatureBuilder()
                .newControl().identifier("Room").arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier("Lamp").arity(FiniteOrdinal.ofInteger(1)).assign()
                .create();

            PureBigraphBuilder<DynamicSignature> bParallelWorkspace = pureBuilder(sigParallelWorkspace);
            BigraphEntity.OuterName power = bParallelWorkspace.createOuter("power");
            PureBigraph parallelWorkspace = bParallelWorkspace
                .root()
                    .child("Room").down()
                        .child("Lamp").linkOuter(power)
                    .up()
                .create();
            exportTo(sigParallelWorkspace, parallelWorkspace, playgroundBase + "/parallel", "workspace");

            DynamicSignature sigParallelAddon = pureSignatureBuilder()
                .newControl().identifier("Room").arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier("Thermostat").arity(FiniteOrdinal.ofInteger(1)).assign()
                .create();

            PureBigraphBuilder<DynamicSignature> bParallelAddon = pureBuilder(sigParallelAddon);
            BigraphEntity.OuterName heating = bParallelAddon.createOuter("heating");
            PureBigraph parallelAddon = bParallelAddon
                .root()
                    .child("Room").down()
                        .child("Thermostat").linkOuter(heating)
                    .up()
                .create();
            exportTo(sigParallelAddon, parallelAddon, playgroundBase + "/parallel", "addon");

            // ── sequential ────────────────────────────────────────────────────
            DynamicSignature sigSeqWorkspace = pureSignatureBuilder()
                .newControl().identifier("Building").arity(FiniteOrdinal.ofInteger(0)).assign()
                .create();

            PureBigraphBuilder<DynamicSignature> bSeqWorkspace = pureBuilder(sigSeqWorkspace);
            PureBigraph seqWorkspace = bSeqWorkspace
                .root()
                    .child("Building").down()
                        .site()
                    .up()
                .create();
            exportTo(sigSeqWorkspace, seqWorkspace, playgroundBase + "/sequential", "workspace");

            DynamicSignature sigSeqContent = pureSignatureBuilder()
                .newControl().identifier("Building").arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier("Person").arity(FiniteOrdinal.ofInteger(0)).assign()
                .create();

            PureBigraphBuilder<DynamicSignature> bSeqContent = pureBuilder(sigSeqContent);
            PureBigraph seqContent = bSeqContent
                .root()
                    .child("Person")
                .create();
            exportTo(sigSeqContent, seqContent, playgroundBase + "/sequential", "content");

            LOGGER.info("✅ Composition examples exported to {}", playgroundBase);

        } catch (Exception e) {
            LOGGER.error("❌ Failed to export composition examples: {}", e.getMessage(), e);
        }
    }

    /** Exports signature (.ecore + .xmi) and bigraph (.xmi) into the given directory with the given base name. */
    private static void exportTo(DynamicSignature signature, PureBigraph bigraph, String dir, String baseName) throws IOException {
        File outputDir = new File(dir);
        outputDir.mkdirs();

        try (FileOutputStream ecoreOut = new FileOutputStream(new File(outputDir, baseName + ".signature.ecore"))) {
            BigraphFileModelManagement.Store.exportAsMetaModel(signature, ecoreOut);
        }
        try (FileOutputStream xmiSigOut = new FileOutputStream(new File(outputDir, baseName + ".signature.xmi"))) {
            BigraphFileModelManagement.Store.exportAsInstanceModel(signature, xmiSigOut);
        }
        try (FileOutputStream xmiOut = new FileOutputStream(new File(outputDir, baseName + ".xmi"))) {
            BigraphFileModelManagement.Store.exportAsInstanceModel(bigraph, xmiOut);
        }
        LOGGER.info("📄 Exported {}/{}", dir, baseName);
    }

    private static PureBigraph createBigraph1(DynamicSignature signature) throws LinkTypeNotExistsException, TypeNotExistsException, InvalidConnectionException {


        
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(signature);
        
        // Create Inner Names for Hyper Edges (connecting specific named instances)
        BigraphEntity.InnerName sensorNetwork = builder.createInner("sensor_network");
        BigraphEntity.InnerName controlBus = builder.createInner("control_bus");
        BigraphEntity.InnerName emergencyChannel = builder.createInner("emergency_channel");  // Keep as InnerName
        
        // Create Outer Names (external interfaces)  
        BigraphEntity.OuterName powerSupply = builder.createOuter("power_supply");
        BigraphEntity.OuterName networkInterface = builder.createOuter("network_interface");
        
        // Build place graph with named instances and link them to inner/outer names
        builder.root()                                    
            .child("Container")   
                .down()                            
                    
                    .child("Sensor").linkInner(sensorNetwork).linkOuter(powerSupply)
                    .child("Sensor").linkInner(sensorNetwork).linkOuter(powerSupply)
                    .child("Sensor").linkInner(sensorNetwork).linkInner(emergencyChannel)

                    .child("Controller").linkInner(sensorNetwork).linkInner(controlBus).linkOuter(networkInterface)
                    
                    .child("Device").linkInner(controlBus).linkOuter(powerSupply)
                    .child("Device").linkInner(controlBus)
                    .child("Device").linkOuter(networkInterface);

        builder.root()
            .child("Container")
                .down()
                    .child("Device").linkOuter(networkInterface); 
                    
                    //.site()  // SiteEntity - placeholder for additional components
                    
                    // Unconnected instance with external interface
        
        // Convert some Inner Names to Edges, keep others as InnerNames
        builder.closeInner(sensorNetwork);   
        builder.closeInner(controlBus);      
        builder.closeInner(emergencyChannel);  // Close to make bigraph ground for rewrite rules
        
        // Create the final bigraph
        PureBigraph demoBigraph = builder.create();
        
        // Set node names after creation
        // Set node names after creation
        java.util.List<BigraphEntity.NodeEntity> containers = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Container"))
            .collect(java.util.stream.Collectors.toList());
        if (!containers.isEmpty()) {
            containers.get(0).setName("factory_floor");
            if (containers.size() > 1) {
                containers.get(1).setName("server_room");
            }
        }
        
        // Set Sensor names
        java.util.List<BigraphEntity.NodeEntity> sensors = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Sensor"))
            .collect(java.util.stream.Collectors.toList());
        if (sensors.size() >= 3) {
            sensors.get(0).setName("temp_sensor_01");
            sensors.get(1).setName("pressure_sensor_42");
            sensors.get(2).setName("vibration_sensor_x7");
        }
        
        // Set Controller name
        BigraphEntity.NodeEntity controller = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Controller"))
            .findFirst().get();
        controller.setName("main_ctrl_unit");
        
        // Set Device names
        java.util.List<BigraphEntity.NodeEntity> devices = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Device"))
            .collect(java.util.stream.Collectors.toList());
        if (devices.size() >= 3) {
            devices.get(0).setName("robotic_arm_alpha");
            devices.get(1).setName("conveyor_belt_12");
            devices.get(2).setName("display_unit_gamma");
        }
        
        // Step 3: Export Signature and Bigraph
        LOGGER.info("💾 Step 3: Exporting signature and bigraph to XMI...");

        return demoBigraph;
        
    }

    private static DynamicSignature createSignature1() {
        DynamicSignature signature = pureSignatureBuilder()
        .newControl().identifier("Device").arity(FiniteOrdinal.ofInteger(5)).assign()      
        .newControl().identifier("Sensor").arity(FiniteOrdinal.ofInteger(5)).assign()      
        .newControl().identifier("Controller").arity(FiniteOrdinal.ofInteger(5)).assign()  
        .newControl().identifier("Container").arity(FiniteOrdinal.ofInteger(5)).assign()   
        .create();
        return signature;
    }

    /**
     * Creates a simplified demo bigraph (bigraph4) with only outer names, no edges.
     * This version is designed for clean rewrite rule transformations without edge artifacts.
     * Each Sensor has exactly 2 outer name connections, matching the redex pattern.
     * 
     * @param signature The signature to use (should match createSignature1)
     * @return A simplified bigraph with only outer names, no edges
     */
    public static PureBigraph createBigraph4(DynamicSignature signature) throws LinkTypeNotExistsException, TypeNotExistsException, InvalidConnectionException {
        // SIMPLIFIED VERSION: Only outer names, no edges
        // This ensures clean rewrite rule transformations without edge artifacts
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(signature);
        
        // Create Outer Names (external interfaces) - only outer names, no edges
        BigraphEntity.OuterName powerSupply = builder.createOuter("power_supply");
        BigraphEntity.OuterName networkInterface = builder.createOuter("network_interface");
        BigraphEntity.OuterName dataLink = builder.createOuter("data_link");
        
        // Build place graph with named instances linked only to outer names
        // Each Sensor has 2 ports connected to outer names (matching the redex pattern)
        builder.root()                                    
            .child("Container")   
                .down()                            
                    // Sensors with 2 outer name connections (matches redex pattern)
                    .child("Sensor").linkOuter(powerSupply).linkOuter(dataLink)
                    .child("Sensor").linkOuter(powerSupply).linkOuter(dataLink)
                    .child("Sensor").linkOuter(powerSupply).linkOuter(networkInterface)

                    .child("Controller").linkOuter(dataLink).linkOuter(networkInterface)
                    
                    .child("Device").linkOuter(powerSupply).linkOuter(dataLink)
                    .child("Device").linkOuter(networkInterface);

        builder.root()
            .child("Container")
                .down()
                    .child("Device").linkOuter(networkInterface);
        
        // Create the final bigraph
        PureBigraph demoBigraph = builder.create();
        
        // Set node names after creation
        java.util.List<BigraphEntity.NodeEntity> containers = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Container"))
            .collect(java.util.stream.Collectors.toList());
        if (!containers.isEmpty()) {
            containers.get(0).setName("server_room");
            if (containers.size() > 1) {
                containers.get(1).setName("factory_floor");
            }
        }
        
        // Set Sensor names
        java.util.List<BigraphEntity.NodeEntity> sensors = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Sensor"))
            .collect(java.util.stream.Collectors.toList());
        if (sensors.size() >= 3) {
            sensors.get(0).setName("temp_sensor_01");
            sensors.get(1).setName("pressure_sensor_42");
            sensors.get(2).setName("vibration_sensor_x7");
        }
        
        // Set Controller name
        BigraphEntity.NodeEntity controller = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Controller"))
            .findFirst().get();
        controller.setName("main_ctrl_unit");
        
        // Set Device names
        java.util.List<BigraphEntity.NodeEntity> devices = demoBigraph.getNodes().stream()
            .filter(node -> node.getControl().getNamedType().stringValue().equals("Device"))
            .collect(java.util.stream.Collectors.toList());
        if (devices.size() >= 3) {
            devices.get(0).setName("robotic_arm_alpha");
            devices.get(1).setName("conveyor_belt_12");
            devices.get(2).setName("display_unit_gamma");
        }
        
        return demoBigraph;
    }

    /**
     * Creates the redex (left-hand side) of a rewrite rule.
     * This bigraph matches a Sensor node inside a Container with a site for other siblings.
     * The Sensor's ports are connected to outer names that act as placeholders for the
     * actual links in the agent (edges or outer names).
     * Rule pattern: Container[site | Sensor{link1, link2}]
     * 
     * @param signature The signature to use (should match createSignature1)
     * @return A bigraph representing the redex (pattern to match)
     */
    public static PureBigraph createBigraph2(DynamicSignature signature) throws LinkTypeNotExistsException, TypeNotExistsException, InvalidConnectionException {
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(signature);
        
        // Outer names act as placeholders for whatever links the matched Sensor has.
        // Each Sensor in the agent has 2 connected ports, so we need 2 outer names.
        BigraphEntity.OuterName link1 = builder.createOuter("link1");
        BigraphEntity.OuterName link2 = builder.createOuter("link2");
        
        // Build the redex: Container[site | Sensor{link1, link2}]
        // The site abstracts sibling nodes in the place graph.
        // The outer names abstract the Sensor's port connections in the link graph.
        PureBigraph redex = builder.root()
            .child("Container")
            .down()
            .site()
            .child("Sensor").linkOuter(link1).linkOuter(link2)
            .up()
            .create();
        
        return redex;
    }

    /**
     * Creates the reactum (right-hand side) of a rewrite rule.
     * This bigraph replaces the matched Sensor with a Device, preserving the same
     * link connections via the same outer names (link1, link2) as in the redex.
     * Rule replacement: Container[site | Device{link1, link2}]
     * 
     * @param signature The signature to use (should match createSignature1)
     * @return A bigraph representing the reactum (replacement pattern)
     */
    public static PureBigraph createBigraph3(DynamicSignature signature) throws LinkTypeNotExistsException, TypeNotExistsException, InvalidConnectionException {
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(signature);
        
        // Same outer names as the redex — the Device inherits the Sensor's connections.
        BigraphEntity.OuterName link1 = builder.createOuter("link1");
        BigraphEntity.OuterName link2 = builder.createOuter("link2");
        
        // Build the reactum: Container[site | Device{link1, link2}]
        // Same structure as redex but Sensor is replaced with Device,
        // keeping the same link interface so the connections are preserved.
        PureBigraph reactum = builder.root()
            .child("Container")
            .down()
            .site()
            .child("Device").linkOuter(link1).linkOuter(link2)
            .up()
            .create();
        
        return reactum;
    }

    /**
     * Creates a signature matching createSignature1.
     * This signature is used for the redex bigraph (createBigraph2).
     * 
     * @return A signature with Device, Sensor, Controller, and Container controls
     */
    private static DynamicSignature createSignature2() {
        DynamicSignature signature = pureSignatureBuilder()
            .newControl().identifier("Device").arity(FiniteOrdinal.ofInteger(5)).assign()      
            .newControl().identifier("Sensor").arity(FiniteOrdinal.ofInteger(5)).assign()      
            .newControl().identifier("Controller").arity(FiniteOrdinal.ofInteger(5)).assign()  
            .newControl().identifier("Container").arity(FiniteOrdinal.ofInteger(5)).assign()   
            .create();
        return signature;
    }

    /**
     * Creates a signature matching createSignature1.
     * This signature is used for the reactum bigraph (createBigraph3).
     * 
     * @return A signature with Device, Sensor, Controller, and Container controls
     */
    private static DynamicSignature createSignature3() {
        DynamicSignature signature = pureSignatureBuilder()
            .newControl().identifier("Device").arity(FiniteOrdinal.ofInteger(5)).assign()      
            .newControl().identifier("Sensor").arity(FiniteOrdinal.ofInteger(5)).assign()      
            .newControl().identifier("Controller").arity(FiniteOrdinal.ofInteger(5)).assign()  
            .newControl().identifier("Container").arity(FiniteOrdinal.ofInteger(5)).assign()   
            .create();
        return signature;
    }

    private static void exportSignatureAndBigraph(DynamicSignature signature, PureBigraph bigraph) {
        exportSignatureAndBigraph(signature, bigraph, null);
    }
    
    /**
     * Export both signature and bigraph as XMI files for serialization and persistence.
     * Creates two files:
     * - {yyyy-MM-dd-HHmmss}.signature.xmi (contains control definitions)
     * - {yyyy-MM-dd-HHmmss}.xmi (contains bigraph instance)
     */
    private static void exportSignatureAndBigraph(DynamicSignature signature, PureBigraph bigraph, String filename) {
        try {
            LOGGER.info("💾 Exporting signature and bigraph as XMI...");
            
            File outputDir = new File("./output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // Generate a timestamp-based base name for both files
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
            String randomId = generateRandomId();
            String baseFileName = LocalDateTime.now().format(formatter) + "_" + randomId;
            if (filename != null) {
                baseFileName = filename;
            }
            
            // Export Signature Metamodel (.signature.ecore)
            try {
                File signatureMetaModelFile = new File(outputDir, baseFileName + ".signature.ecore");
                FileOutputStream signatureMetaModelOutputStream = new FileOutputStream(signatureMetaModelFile);
                
                BigraphFileModelManagement.Store.exportAsMetaModel(signature, signatureMetaModelOutputStream);
                signatureMetaModelOutputStream.close();
                
                LOGGER.info("📄 Signature metamodel exported: {}", signatureMetaModelFile.getAbsolutePath());
            } catch (IOException metamodelException) {
                LOGGER.error("❌ Signature metamodel export failed: {}", metamodelException.getMessage());
                throw metamodelException;
            }
            
            // Export Signature Instance (.signature.xmi)
            try {
                File signatureInstanceFile = new File(outputDir, baseFileName + ".signature.xmi");
                FileOutputStream signatureInstanceOutputStream = new FileOutputStream(signatureInstanceFile);
                
                BigraphFileModelManagement.Store.exportAsInstanceModel(signature, signatureInstanceOutputStream);
                signatureInstanceOutputStream.close();
                
                LOGGER.info("📄 Signature instance exported: {}", signatureInstanceFile.getAbsolutePath());
            } catch (IOException instanceException) {
                LOGGER.error("❌ Signature instance export failed: {}", instanceException.getMessage());
                throw instanceException;
            }
            
            // Export Bigraph
            try {
                File bigraphFile = new File(outputDir, baseFileName + ".xmi");
                FileOutputStream bigraphOutputStream = new FileOutputStream(bigraphFile);
                
                BigraphFileModelManagement.Store.exportAsInstanceModel(bigraph, bigraphOutputStream);
                bigraphOutputStream.close();
                
                LOGGER.info("📄 Bigraph exported: {}", bigraphFile.getAbsolutePath());
            } catch (IOException bigraphException) {
                LOGGER.error("❌ Bigraph export failed: {}", bigraphException.getMessage());
                throw bigraphException;
            }
            
            LOGGER.info("✅ Successfully exported signature (metamodel + instance) and bigraph");
            
        } catch (Exception e) {
            LOGGER.error("❌ Failed to export signature and bigraph: {}", e.getMessage());
            throw new RuntimeException("Export failed", e);
        }
    }
    
    
    /**
     * Export the demo bigraph as PNG for visual inspection (requires Graphviz)
     */
    private static void exportDemoBigraphAsPNG(PureBigraph bigraph) {
        try {
            LOGGER.info("📸 Attempting to export bigraph visualization...");
            
            File outputDir = new File("./output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // Try to export as PNG using Graphviz if available
            try {
                BigraphGraphvizExporter.toPNG(bigraph, true, new File(outputDir, "clean_demo_bigraph.png"));
                LOGGER.info("💾 Bigraph exported as: ./output/clean_demo_bigraph.png");
            } catch (Exception exportException) {
                LOGGER.warn("⚠️ Graphviz export not available: " + exportException.getMessage());
                LOGGER.info("📋 Bigraph structure created successfully (export skipped)");
            }
            
        } catch (Exception e) {
            LOGGER.warn("⚠️ Could not export bigraph visualization: " + e.getMessage());
        }
    }
    
    /**
     * Reusable function that applies a rewrite rule to a bigraph.
     * This function takes a main bigraph, a redex (pattern to match), and a reactum (replacement),
     * creates a reaction rule, and applies it to transform the main bigraph.
     * 
     * This function can be reused with different bigraphs and rules.
     * 
     * @param mainBigraph The bigraph to transform (the host graph)
     * @param redex The left-hand side of the rule (pattern to match)
     * @param reactum The right-hand side of the rule (replacement pattern)
     * @param ruleLabel Optional label for the rule (can be null)
     * @return The transformed bigraph after applying the rewrite rule, or null if no matches found or error occurred
     */
    public static PureBigraph applyRewriteRule(PureBigraph mainBigraph, PureBigraph redex, PureBigraph reactum, String ruleLabel) {
        try {
            LOGGER.info("🔄 Applying rewrite rule operation...");
            
            // Step 1: Create the parametric reaction rule
            LOGGER.info("📋 Creating reaction rule from redex and reactum...");
            ParametricReactionRule<PureBigraph> rule;
            if (ruleLabel != null && !ruleLabel.isEmpty()) {
                rule = new ParametricReactionRule<>(redex, reactum).withLabel(ruleLabel);
            } else {
                rule = new ParametricReactionRule<>(redex, reactum);
            }
            LOGGER.info("✅ Reaction rule created");
            
            // Step 2: Create reactive system and add the rule
            LOGGER.info("📋 Setting up reactive system...");
            PureReactiveSystem rs = new PureReactiveSystem();
            rs.setAgent(mainBigraph);
            rs.addReactionRule(rule);
            LOGGER.info("✅ Reactive system configured");
            
            // Step 3: Find matches of the rule in the main bigraph
            LOGGER.info("📋 Searching for matches in main bigraph...");
            AbstractBigraphMatcher<PureBigraph> matcher = AbstractBigraphMatcher.create(PureBigraph.class);
            MatchIterable<?> matches = matcher.match(mainBigraph, rule);
            
            Iterator<?> iterator = matches.iterator();
            
            // Step 4: Apply the rule to the first match
            // Note: MatchIterable is single-use, so we grab the first match directly
            // and count remaining matches without exhausting it first.
            if (iterator.hasNext()) {
                @SuppressWarnings("unchecked")
                BigraphMatch<PureBigraph> firstMatch = (BigraphMatch<PureBigraph>) iterator.next();
                int matchCount = 1;
                while (iterator.hasNext()) {
                    iterator.next();
                    matchCount++;
                }
                LOGGER.info("🔍 Found {} match(es) for the rule", matchCount);
                
                LOGGER.info("📋 Applying rewrite rule to first match...");
                PureBigraph transformedBigraph = rs.buildParametricReaction(mainBigraph, firstMatch, rule);
                
                if (transformedBigraph != null) {
                    LOGGER.info("✅ Rewrite rule applied successfully!");
                    LOGGER.info("📊 Original bigraph had {} nodes", mainBigraph.getNodes().size());
                    LOGGER.info("📊 Transformed bigraph has {} nodes", transformedBigraph.getNodes().size());
                    return transformedBigraph;
                } else {
                    LOGGER.warn("⚠️ Rule application returned null");
                    return null;
                }
            } else {
                LOGGER.info("🔍 Found 0 match(es) for the rule");
                LOGGER.warn("⚠️ No matches found for the rule. Cannot apply transformation.");
                return null;
            }
            
        } catch (InvalidReactionRuleException e) {
            LOGGER.error("❌ Invalid reaction rule: " + e.getMessage(), e);
            return null;
        } catch (Exception e) {
            LOGGER.error("❌ Error applying rewrite rule: " + e.getMessage(), e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Complete rewrite rule operation: Creates the main bigraph, builds a rewrite rule
     * from the redex and reactum bigraphs, and applies it to transform the main bigraph.
     * 
     * This function:
     * 1. Creates the signature
     * 2. Creates the main bigraph (createBigraph1)
     * 3. Creates the redex bigraph (createBigraph2) - the pattern to match
     * 4. Creates the reactum bigraph (createBigraph3) - the replacement pattern
     * 5. Calls applyRewriteRule to perform the actual transformation
     * 6. Returns the transformed bigraph
     * 
     * Rule: Container[site | Sensor{link1,link2}] -> Container[site | Device{link1,link2}]
     * This transforms any Sensor (with 2 connected ports) inside a Container to a Device,
     * preserving the link connections.
     * 
     * @return The transformed bigraph after applying the rewrite rule, or null if no matches found
     */
    public static PureBigraph applyRewriteRuleOperation() {
        try {
            LOGGER.info("🚀 Starting complete rewrite rule operation...");
            
            // Step 1: Create the signature
            LOGGER.info("📋 Step 1: Creating signature...");
            DynamicSignature signature = createSignature1();
            LOGGER.info("✅ Signature created with controls: Device, Sensor, Controller, Container");
            
            // Step 2: Create the main bigraph (the one we want to transform)
            LOGGER.info("📋 Step 2: Creating main bigraph...");
            PureBigraph mainBigraph = createBigraph1(signature);
            LOGGER.info("✅ Main bigraph created with {} nodes", mainBigraph.getNodes().size());
            
            // Step 3: Create the redex (left-hand side of the rule - pattern to match)
            LOGGER.info("📋 Step 3: Creating redex bigraph (pattern to match)...");
            PureBigraph redex = createBigraph2(signature);
            LOGGER.info("✅ Redex created: Container[site | Sensor]");
            
            // Step 4: Create the reactum (right-hand side of the rule - replacement pattern)
            LOGGER.info("📋 Step 4: Creating reactum bigraph (replacement pattern)...");
            PureBigraph reactum = createBigraph3(signature);
            LOGGER.info("✅ Reactum created: Container[site | Device]");
            
            // Step 5-8: Apply the rewrite rule using the reusable function
            PureBigraph transformedBigraph = applyRewriteRule(mainBigraph, redex, reactum, "sensorToDeviceRule");
            return transformedBigraph;
            
        } catch (Exception e) {
            LOGGER.error("❌ Error in rewrite rule operation: " + e.getMessage(), e);
            e.printStackTrace();
            return null;
        }
    }
} 