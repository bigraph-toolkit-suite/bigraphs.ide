package org.eclipse.glsp.example.bigraph.testsupport;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.extensions.ModelVariantRegistry;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.handler.BigraphChangeBoundsOperationHandler;
import org.eclipse.glsp.example.bigraph.handler.PublishEvolutionStateActionHandler;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphXMIModelStorage;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;

public final class BigraphTestSupport {
    private BigraphTestSupport() {}

    public record ControlSpec(String name, int arity) {}

    public static DynamicSignature signature(final ControlSpec... controls) {
        var builder = pureSignatureBuilder();
        for (ControlSpec control : controls) {
            builder.newControl()
                .identifier(control.name())
                .arity(FiniteOrdinal.ofInteger(control.arity()))
                .assign();
        }
        return builder.create();
    }

    public static PureBigraphMutable emptyBigraph(final DynamicSignature signature) {
        PureBigraph empty = pureBuilder(signature)
            .root()
            .create();
        return PureBigraphBuilder
            .create(signature, empty.getMetaModel(), empty.getInstanceModel())
            .createMutable();
    }

    public static RootEntity firstRoot(final PureBigraphMutable bigraph) {
        return bigraph.getRoots().stream().findFirst().orElseThrow();
    }

    public static DynamicControl control(final DynamicSignature signature, final String name) {
        return signature.getControls().stream()
            .filter(DynamicControl.class::isInstance)
            .map(DynamicControl.class::cast)
            .filter(control -> name.equals(control.getNamedType().stringValue()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown control: " + name));
    }

    public static BigraphEntity.NodeEntity<DynamicControl> addNode(final PureBigraphMutable bigraph,
                                                                   final BigraphEntity<?> parent,
                                                                   final String controlName,
                                                                   final String nodeName) {
        BigraphEntity.NodeEntity<DynamicControl> node =
            bigraph.addNode(parent, control(bigraph.getSignature(), controlName), null);
        node.setName(nodeName);
        return node;
    }

    public static BigraphModelState initializedState(final PureBigraphMutable bigraph) {
        return initializedState(bigraph, new BigraphMetaInformation());
    }

    public static BigraphModelState initializedState(final PureBigraphMutable bigraph,
                                                     final BigraphMetaInformation metaInformation) {
        BigraphModelState state = new BigraphModelState();
        state.init();
        state.initializeBigraphModel(bigraph, metaInformation);
        return state;
    }

    /**
     * Build a real {@link VariantGate} backed by the given test state and a
     * fresh {@link ModelVariantRegistry}. Use {@link #inject(Object, String, Object)}
     * to wire it into handlers that would otherwise pick it up via Guice.
     */
    public static VariantGate variantGateFor(final BigraphModelState state) {
        return new VariantGate(state, new ModelVariantRegistry());
    }

    public static GPoint point(final double x, final double y) {
        GPoint point = GraphFactory.eINSTANCE.createGPoint();
        point.setX(x);
        point.setY(y);
        return point;
    }

    public static GDimension size(final double width, final double height) {
        GDimension dimension = GraphFactory.eINSTANCE.createGDimension();
        dimension.setWidth(width);
        dimension.setHeight(height);
        return dimension;
    }

    public static Path createFile(final Path directory, final String fileName, final String content) throws Exception {
        Path file = directory.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    /**
     * Creates a valid .xmi bigraph file with companion .signature.ecore and .signature.xmi files.
     * Returns the path to the .xmi file.
     */
    public static Path createValidXmiFixture(final Path directory, final String baseName) throws IOException {
        Path xmiPath = directory.resolve(baseName + ".xmi");
        BigraphIO.createEmptyBigraphFile(xmiPath.toFile());
        return xmiPath;
    }

    /**
     * Creates a minimal bigraph with one node under the root for query/agent tests.
     */
    public static PureBigraphMutable bigraphWithOneNode(final String controlName, final String nodeName) {
        DynamicSignature sig = signature(new ControlSpec(controlName, 0));
        PureBigraphMutable bigraph = emptyBigraph(sig);
        addNode(bigraph, firstRoot(bigraph), controlName, nodeName);
        return bigraph;
    }

    /**
     * Creates a bigraph with a site under the root and one node inside the site.
     */
    public static PureBigraphMutable bigraphWithSiteAndNode(final String controlName, final String nodeName) {
        DynamicSignature sig = signature(new ControlSpec(controlName, 0));
        PureBigraphMutable bigraph = emptyBigraph(sig);
        RootEntity root = firstRoot(bigraph);
        SiteEntity site = bigraph.addSite(root);
        addNode(bigraph, site, controlName, nodeName);
        return bigraph;
    }

    public static void inject(final Object target, final String fieldName, final Object value) {
        boolean updated = false;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                updated = true;
            } catch (NoSuchFieldException ignored) {
                // Search next class in hierarchy.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to inject field '" + fieldName + "'", exception);
            }
        }
        if (!updated) {
            throw new IllegalArgumentException("Field '" + fieldName + "' not found on " + target.getClass().getName());
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T readField(final Object target, final String fieldName, final Class<T> type) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Search next class in hierarchy.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read field '" + fieldName + "'", exception);
            }
        }
        throw new IllegalArgumentException("Field '" + fieldName + "' not found on " + target.getClass().getName());
    }

    public static final class RecordingActionDispatcher implements ActionDispatcher {
        private final List<Action> dispatched = new ArrayList<>();
        private final List<Action> queuedAfterNextUpdate = new ArrayList<>();
        private boolean disposed;

        @Override
        public CompletableFuture<Void> dispatch(final Action action) {
            dispatched.add(action);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void dispatchAfterNextUpdate(final Action... actions) {
            queuedAfterNextUpdate.addAll(Arrays.asList(actions));
        }

        public List<Action> getDispatched() {
            return Collections.unmodifiableList(dispatched);
        }

        public List<Action> getQueuedAfterNextUpdate() {
            return Collections.unmodifiableList(queuedAfterNextUpdate);
        }

        public <T extends Action> List<T> dispatchedOfType(final Class<T> type) {
            return dispatched.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    public static final class TestableBigraphXMIModelStorage extends BigraphXMIModelStorage {
        public Optional<GModelRoot> loadForTest(final File file, final BigraphModelState state) {
            return super.loadSourceModel(file, state);
        }
    }

    public static final class TestablePublishEvolutionStateActionHandler extends PublishEvolutionStateActionHandler {
        public List<Action> executeForTest(final PublishEvolutionStateAction action) {
            return super.executeAction(action);
        }
    }

    public static final class TestableBigraphChangeBoundsOperationHandler extends BigraphChangeBoundsOperationHandler {
        public void changeBoundsForTest(final String elementId, final GPoint newPosition, final GDimension newSize) {
            super.changeElementBounds(elementId, newPosition, newSize);
        }
    }
}
