package org.eclipse.glsp.example.bigraph.extension.popp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The POPP packages form a total order: a package only refers to packages before it (imports and fully qualified
 * names, Javadoc included), and the inner packages only to the libraries they are meant to use. Reads the sources, so
 * it runs from the module directory (as Maven does).
 * <pre>
 *   types        the domain model and its events     (nothing)
 *   io           JSON format, meta-file data          types, Gson
 *   bigraph      the bigraph encoding                 types, Bigraph Framework
 *   gmodel       the GLSP graph model                 types, GLSP
 *   coverage     coverage semantics and analyzers     types, bigraph, Bigraph Framework
 *   validation   validation patterns                  types, bigraph, Bigraph Framework
 *   state        per-diagram state                    everything above
 *   actions      GLSP actions (DTOs)                  everything above
 *   sync         keeps domain, GLSP and bigraph model  everything above
 *                consistent: synchronizers, loader
 *   handler      what GLSP and the IDE call: action    everything above
 *                handlers, edge creation checker, the
 *                validation service
 *   (root)       POPPExtension, the composition root  everything
 * </pre>
 */
class PackageLayeringTest {
    private static final String BASE = "org.eclipse.glsp.example.bigraph.extension.popp";
    private static final Path SOURCES = Path.of("src/main/java", BASE.split("\\."));

    private static final List<String> ORDER = List.of("types", "io", "bigraph", "gmodel", "coverage", "validation",
            "state", "actions", "sync", "handler", "");

    /** External libraries the inner packages may use; packages not listed may use any. */
    private static final Map<String, Set<String>> LIBRARIES = Map.of(
            "types", Set.of(),
            "io", Set.of("com.google.gson"),
            "bigraph", Set.of("org.bigraphs", "it.uniud", "org.eclipse.emf"),
            "gmodel", Set.of("org.eclipse.glsp.graph", "org.eclipse.glsp.server", "org.eclipse.glsp.example.bigraph.extensions"),
            "coverage", Set.of("org.bigraphs", "it.uniud", "org.eclipse.emf"),
            "validation", Set.of("org.bigraphs", "it.uniud", "org.eclipse.emf"));

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern QUALIFIED = Pattern.compile(Pattern.quote(BASE) + "(?:\\.[a-z]\\w*)*\\.[A-Z]\\w*");
    /** Libraries every package may use. */
    private static final List<String> ALWAYS = List.of("java.", "javax.", "org.slf4j", "org.apache.logging");

    @Test
    void everyPackageOnlyRefersToPackagesBeforeIt() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "run from the module directory: " + SOURCES.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher pm = PACKAGE.matcher(source);
                assertTrue(pm.find(), "no package declaration in " + file);
                String own = layer(pm.group(1));
                int rank = ORDER.indexOf(own);
                assertTrue(rank >= 0, "package " + pm.group(1) + " is not in the layer order");
                String body = source.substring(pm.end());

                Set<String> refs = new TreeSet<>();
                IMPORT.matcher(body).results().forEach(m -> refs.add(m.group(1)));
                QUALIFIED.matcher(body).results().forEach(m -> refs.add(m.group()));
                for (String ref : refs) {
                    String name = file.getFileName() + " -> " + ref;
                    if (ref.startsWith(BASE + ".") || ref.equals(BASE)) {
                        String target = layer(ref.substring(0, Math.max(ref.lastIndexOf('.'), 0)));
                        if (!target.equals(own) && ORDER.indexOf(target) >= rank) violations.add(name);
                    } else if (LIBRARIES.containsKey(own)
                            && ALWAYS.stream().noneMatch(ref::startsWith)
                            && LIBRARIES.get(own).stream().noneMatch(ref::startsWith)) {
                        violations.add(name + " (library not allowed in " + own + ")");
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    /** The layer of a package (or of a class's package): its first segment below the POPP package. */
    private static String layer(String pkg) {
        if (!pkg.startsWith(BASE)) return pkg;
        String rest = pkg.substring(BASE.length()).replaceFirst("^\\.", "");
        String first = rest.isEmpty() ? "" : rest.split("\\.")[0];
        return Character.isUpperCase(first.isEmpty() ? 'a' : first.charAt(0)) ? "" : first;
    }
}
