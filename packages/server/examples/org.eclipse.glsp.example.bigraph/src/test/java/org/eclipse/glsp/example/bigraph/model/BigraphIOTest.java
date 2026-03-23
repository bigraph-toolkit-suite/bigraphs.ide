package org.eclipse.glsp.example.bigraph.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BigraphIOTest {

    @TempDir
    Path tempDir;

    @Test
    void parseBigraphFromFileRejectsDirectSignatureInputs() {
        BigraphLoadException xmiException = assertThrows(
            BigraphLoadException.class,
            () -> BigraphIO.parseBigraphFromFile(tempDir.resolve("diagram.signature.xmi").toFile()));
        BigraphLoadException ecoreException = assertThrows(
            BigraphLoadException.class,
            () -> BigraphIO.parseBigraphFromFile(tempDir.resolve("diagram.signature.ecore").toFile()));

        assertTrue(xmiException.getMessage().contains("Cannot open signature files directly"));
        assertTrue(ecoreException.getMessage().contains("Cannot open signature files directly"));
    }

    @Test
    void parseBigraphFromFileInitializesBlankPlaceholderBigraphs() throws Exception {
        Path bigraphPath = tempDir.resolve("blank.xmi");
        Files.createFile(bigraphPath);

        PureBigraphMutable loaded = BigraphIO.parseBigraphFromFile(bigraphPath.toFile());
        PureBigraphMutable loadedAgain = BigraphIO.parseBigraphFromFile(bigraphPath.toFile());

        assertEquals(1, loaded.getRoots().size());
        assertEquals(1, loadedAgain.getRoots().size());
        assertTrue(Files.size(bigraphPath) > 0L);
        assertTrue(Files.exists(tempDir.resolve("blank.signature.ecore")));
        assertTrue(Files.exists(tempDir.resolve("blank.signature.xmi")));
    }

    @Test
    void parseBigraphFromFileFailsWhenOnlyOneCompanionSignatureExists() throws Exception {
        Path bigraphPath = tempDir.resolve("incomplete.xmi");
        Files.createFile(bigraphPath);
        Files.writeString(tempDir.resolve("incomplete.signature.xmi"), "<signature/>");

        BigraphLoadException exception = assertThrows(
            BigraphLoadException.class,
            () -> BigraphIO.parseBigraphFromFile(bigraphPath.toFile()));

        assertTrue(exception.getMessage().contains("Missing companion signature files"));
        assertTrue(exception.getMessage().contains("incomplete.xmi"));
    }

    @Test
    void createEmptyBigraphReturnsValidEmptyModel() {
        var empty = BigraphIO.createEmptyBigraph();
        assertNotNull(empty);
        assertEquals(1, empty.getRoots().size());
        assertNotNull(empty.getSignature());
    }
}
