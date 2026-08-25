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

import org.eclipse.glsp.example.bigraph.handler.CreateBigraphNodeOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateBigraphSiteOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateConnectionOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateEdgeOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateInnerNameOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateOuterNameOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.DeleteBigraphElementOperationHandler;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphXMIModelStorage;
import org.eclipse.glsp.example.bigraph.provider.BigraphToolPaletteItemProvider;
import org.eclipse.glsp.server.diagram.DiagramConfiguration;
import org.eclipse.glsp.server.features.core.model.SourceModelStorage;
import org.eclipse.glsp.server.features.toolpalette.ToolPaletteItemProvider;
import org.eclipse.glsp.server.gmodel.GModelDiagramModule;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.server.operations.OperationHandler;
import org.eclipse.glsp.server.di.MultiBinding;
import org.eclipse.glsp.example.bigraph.handler.CreateBigraphActionHandler;
import org.eclipse.glsp.example.bigraph.handler.CreateBigraphControlActionHandler;
import org.eclipse.glsp.example.bigraph.handler.TestRewriteRuleActionHandler;
import org.eclipse.glsp.example.bigraph.handler.RenameNodeActionHandler;
import org.eclipse.glsp.example.bigraph.handler.RequestVariantSwitchActionHandler;
import org.eclipse.glsp.example.bigraph.handler.ConvertNameRoleActionHandler;
import org.eclipse.glsp.example.bigraph.handler.BigraphChangeBoundsOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.FileDroppedActionHandler;
import org.eclipse.glsp.example.bigraph.handler.EvolutionRunActionHandler;
import org.eclipse.glsp.example.bigraph.handler.VerifyBigraphActionHandler;
import org.eclipse.glsp.example.bigraph.handler.ComposeBigraphActionHandler;
import org.eclipse.glsp.example.bigraph.handler.AutoLayoutActionHandler;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphSummaryActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphRootsActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphChildrenActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphNodeInfoActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphFindByControlActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphSignatureActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphLinksActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphNeighborsActionHandler;
import org.eclipse.glsp.example.bigraph.handler.PublishEvolutionStateActionHandler;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionAwareEdgeCreationChecker;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionList;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionStateRepository;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.extensions.ModelVariantRegistry;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.layout.ElkLayoutEngine;

import com.google.inject.Singleton;


/**
 * Diagram module configuration for Bigraph XMI support.
 * This module configures the GLSP server to handle XMI files containing Bigraph models.
 */
public class BigraphXMIDiagramModule extends GModelDiagramModule {

    @Override
    protected void configure() {
        super.configure();
        bind(BigraphQueryHelper.class).in(Singleton.class);
        bind(ExtensionStateRepository.class).in(Singleton.class);
        // Extensions inject IBigraphModelState; GModelState is already bound to
        // BigraphModelState by the parent module — this alias shares the same instance.
        bind(IBigraphModelState.class).to(BigraphModelState.class);
        // Variant discovery + runtime gating. Both are diagram-scoped singletons
        // so they live for the lifetime of one diagram session.
        bind(ModelVariantRegistry.class).in(Singleton.class);
        bind(VariantGate.class).in(Singleton.class);
        for (IdeExtension ext : ExtensionList.getInstance().getExtensions()) {
            ext.configure(binder());
        }
    }

    @Override
    protected Class<? extends DiagramConfiguration> bindDiagramConfiguration() {
        return BigraphDiagramConfiguration.class;
    }

    @Override
    protected Class<? extends SourceModelStorage> bindSourceModelStorage() {
        return BigraphXMIModelStorage.class;
    }

    @Override
    protected Class<? extends GModelState> bindGModelState() {
        return BigraphModelState.class;
    }

    @Override
    protected Class<? extends ToolPaletteItemProvider> bindToolPaletteItemProvider() {
        return BigraphToolPaletteItemProvider.class;
    }

    /**
     * Single {@link org.eclipse.glsp.server.features.typehints.EdgeCreationChecker}
     * binding for the whole diagram. Routes to the checker contributed by the
     * active variant's extension (see {@link IdeExtension#getEdgeCreationChecker()});
     * extensions must not bind the checker themselves.
     */
    @Override
    protected Class<? extends org.eclipse.glsp.server.features.typehints.EdgeCreationChecker> bindEdgeCreationChecker() {
        return ExtensionAwareEdgeCreationChecker.class;
    }

    @Override
    protected void configureActionHandlers(final MultiBinding<org.eclipse.glsp.server.actions.ActionHandler> binding) {
        super.configureActionHandlers(binding);
        
        binding.add(CreateBigraphActionHandler.class);
        binding.add(RenameNodeActionHandler.class);
        binding.add(RequestVariantSwitchActionHandler.class);
        binding.add(ConvertNameRoleActionHandler.class);
        binding.add(CreateBigraphControlActionHandler.class);
        binding.add(TestRewriteRuleActionHandler.class);
        binding.add(FileDroppedActionHandler.class);
        binding.add(EvolutionRunActionHandler.class);
        binding.add(PublishEvolutionStateActionHandler.class);
        binding.add(VerifyBigraphActionHandler.class);
        binding.add(ComposeBigraphActionHandler.class);
        binding.add(AutoLayoutActionHandler.class);

        binding.add(BigraphSummaryActionHandler.class);
        binding.add(BigraphRootsActionHandler.class);
        binding.add(BigraphChildrenActionHandler.class);
        binding.add(BigraphNodeInfoActionHandler.class);
        binding.add(BigraphFindByControlActionHandler.class);
        binding.add(BigraphSignatureActionHandler.class);
        binding.add(BigraphLinksActionHandler.class);
        binding.add(BigraphNeighborsActionHandler.class);

        for (IdeExtension ext : ExtensionList.getInstance().getExtensions()) {
            ext.getActionHandlers().forEach(binding::add);
        }
    }

    @Override
    protected Class<? extends org.eclipse.glsp.server.layout.LayoutEngine> bindLayoutEngine() {
        return ElkLayoutEngine.class;
    }

    @Override
    protected void configureOperationHandlers(final MultiBinding<OperationHandler<?>> binding) {
        super.configureOperationHandlers(binding);
        
        // Register create handlers for place graph (nodes and sites)
        binding.add(CreateBigraphNodeOperationHandler.class);
        binding.add(CreateBigraphSiteOperationHandler.class);
        
        // Register create handlers for link graph (inner/outer names and edges)
        binding.add(CreateInnerNameOperationHandler.class);
        binding.add(CreateOuterNameOperationHandler.class);
        binding.add(CreateEdgeOperationHandler.class);
        binding.add(CreateConnectionOperationHandler.class);
        
        // Override default delete handler with our custom bigraph-aware handler
        // Note: This replaces GModelDeleteOperationHandler from the parent module
        binding.rebind(org.eclipse.glsp.server.gmodel.GModelDeleteOperationHandler.class,
                       DeleteBigraphElementOperationHandler.class);
                       
        // Rebind default change bounds handler with our custom bigraph-aware handler
        binding.rebind(org.eclipse.glsp.server.gmodel.GModelChangeBoundsOperationHandler.class,
                       BigraphChangeBoundsOperationHandler.class);

        for (IdeExtension ext : ExtensionList.getInstance().getExtensions()) {
            ext.getOperationHandlers().forEach(binding::add);
        }
    }

    @Override
    public String getDiagramType() {
        return "bigraph-xmi";
    }
}

