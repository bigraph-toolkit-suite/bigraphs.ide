package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * Checks a POPP bigraph against {@link POPPValidationPatterns} with the Bigraph Framework matcher.
 *
 * <p>Two phases, so that only the first one has to run on the GLSP dispatch thread: {@link #snapshot} copies the
 * live bigraph (place graph and links; descriptions only as labels) into an independent bigraph over the patterns'
 * signature, and
 * {@link #validate} matches every pattern against that copy, which is safe on any thread. A census of the
 * snapshot's controls skips patterns that cannot occur, so a model without, say, any stubs never pays for the stub
 * patterns.</p>
 */
public final class POPPModelValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(POPPModelValidator.class);

    /**
     * Upper bound of occurrences reported per pattern. It only limits the issues: the framework computes all matches
     * of a pattern before the first one is returned, so it does not bound the matching cost.
     */
    static final int MAX_MATCHES_PER_PATTERN = 200;

    private final List<ValidationPattern> patterns;

    public POPPModelValidator() {
        this(POPPValidationPatterns.all());
    }

    public POPPModelValidator(List<ValidationPattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * A copy of the bigraph to validate, never modified after {@link #snapshot}.
     *
     * @param modelVersion {@code POPPModel#getVersion()} at the time of the copy, passed on to the report
     * @param bigraph  the copy, over {@link POPPValidationPatterns#SIGNATURE}
     * @param ownerOf  bigraph node name to the id of the nearest enclosing domain node (the node itself for domain
     *                 nodes); absent for the container and for nodes outside any domain node
     * @param labelOf  bigraph node name to a readable label (domain kind and description)
     * @param census   control name to number of nodes
     * @param unknown  names of nodes whose control is not in the POPP signature (not copied)
     */
    public record Snapshot(long modelVersion, PureBigraph bigraph, Map<String, String> ownerOf,
                           Map<String, String> labelOf, Map<String, Integer> census, List<String> unknown) {
    }

    /** Copies {@code source}; cheap (linear in the bigraph), but must run where {@code source} is not being mutated. */
    @SuppressWarnings("unchecked")
    public static Snapshot snapshot(PureBigraph source, long modelVersion) {
        PureBigraphMutable copy = pureBuilder(POPPValidationPatterns.SIGNATURE).createMutable();
        Map<String, String> ownerOf = new HashMap<>();
        Map<String, String> labelOf = new HashMap<>();
        Map<String, Integer> census = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        Map<BigraphEntity.Link, BigraphEntity.Edge> links = new HashMap<>();

        Deque<Object[]> todo = new ArrayDeque<>(); // {source entity, copy parent, owner id, owner label}
        for (BigraphEntity.RootEntity root : source.getRoots()) {
            BigraphEntity.RootEntity r = copy.addRoot();
            for (BigraphEntity<?> child : source.getChildrenOf(root)) todo.push(new Object[]{child, r, null, null});
        }
        while (!todo.isEmpty()) {
            Object[] item = todo.pop();
            BigraphEntity<?> entity = (BigraphEntity<?>) item[0];
            BigraphEntity<?> parent = (BigraphEntity<?>) item[1];
            String owner = (String) item[2];
            String ownerLabel = (String) item[3];
            if (entity instanceof BigraphEntity.SiteEntity) {
                copy.addSite(parent);
                continue;
            }
            if (!(entity instanceof BigraphEntity.NodeEntity<?> raw)) continue;
            var node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            String controlName = node.getControl().getNamedType().stringValue();
            DynamicControl control = POPPValidationPatterns.SIGNATURE.getControlByName(controlName);
            if (control == null) {
                unknown.add(node.getName());
                continue; // its subtree is not copied either: the copy only contains what the patterns can talk about
            }
            var n = copy.addNode(parent, control, node.getName());
            census.merge(controlName, 1, Integer::sum);

            POPPBigraphSignature kind = POPPBigraphSignature.forNode(node);
            if (POPPBigraphSignature.isKindControl(kind)) {
                owner = node.getName();
                Object description = node.getAttributes().get("description");
                String text = description == null ? "" : description.toString().strip();
                ownerLabel = kind.controlName() + (text.isEmpty() ? " " + shortId(owner) : " \"" + text + "\"");
            }
            if (owner != null) ownerOf.put(node.getName(), owner);
            labelOf.put(node.getName(), ownerLabel != null ? ownerLabel : node.getName());

            for (BigraphEntity.Port port : source.getPorts(node)) {
                BigraphEntity.Link link = source.getLinkOfPoint(port);
                if (link != null) {
                    copy.connectNodeToLink(n, links.computeIfAbsent(link, l -> copy.addEdge(l.getName())));
                }
            }
            for (BigraphEntity<?> child : source.getChildrenOf(node)) {
                todo.push(new Object[]{child, n, owner, ownerLabel});
            }
        }
        return new Snapshot(modelVersion, copy, Map.copyOf(ownerOf), Map.copyOf(labelOf), Map.copyOf(census),
                List.copyOf(unknown));
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Matches every applicable pattern against the snapshot. Thread-safe: touches nothing but the snapshot. */
    public ValidationReport validate(Snapshot snapshot) {
        long t0 = System.nanoTime();
        List<ValidationIssue> issues = new ArrayList<>();
        snapshot.unknown().forEach(name -> issues.add(new ValidationIssue(ValidationIssue.Code.UNKNOWN_CONTROL,
                ValidationIssue.Code.UNKNOWN_CONTROL.severity(),
                name + " has a control outside the POPP signature", List.of(), List.of(name), "signature")));

        PureBigraphMatcher matcher = new PureBigraphMatcher();
        int matched = 0, skipped = 0;
        for (ValidationPattern pattern : patterns) {
            if (!present(pattern.required(), snapshot.census())) {
                skipped++;
                continue;
            }
            matched++;
            try {
                issues.addAll(occurrences(matcher, pattern, snapshot));
            } catch (RuntimeException e) {
                LOGGER.warn("Validation pattern {} failed: {}", pattern.name(), e.toString());
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return ValidationReport.of(snapshot.modelVersion(), issues, ms, matched, skipped);
    }

    private static boolean present(Map<String, Integer> required, Map<String, Integer> census) {
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            if (census.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    private static List<ValidationIssue> occurrences(PureBigraphMatcher matcher, ValidationPattern pattern, Snapshot s) {
        // The framework computes all matches before the first one is returned, so patterns are shaped to have few
        // occurrences, e.g. one root instead of a pair of roots. matchFirst would not help: patterns with
        // minOccurrences or aggregate need every occurrence (and in 2.4.1 it returns no match at all).
        // Symmetric patterns find the same nodes several times: one issue per set of offending nodes.
        Map<Set<String>, List<String>> found = new LinkedHashMap<>();
        int n = 0;
        for (PureBigraphMatch match : matcher.matchAll(s.bigraph(), pattern.rule())) {
            Map<String, String> emb = new HashMap<>();
            match.getJLibMatchResult().emb_nodes.forEach((r, a) -> emb.put(r.getEditable().getName(), a.getEditable().getName()));
            List<String> elements = pattern.subjects().stream().map(emb::get).filter(Objects::nonNull).toList();
            found.putIfAbsent(new HashSet<>(elements), elements);
            if (++n >= MAX_MATCHES_PER_PATTERN) break;
        }
        if (found.size() < pattern.minOccurrences()) return List.of();

        if (pattern.aggregate()) {
            LinkedHashSet<String> all = new LinkedHashSet<>();
            found.values().forEach(all::addAll);
            return List.of(issue(pattern, List.copyOf(all), s));
        }
        return found.values().stream().map(elements -> issue(pattern, elements, s)).toList();
    }

    private static ValidationIssue issue(ValidationPattern pattern, List<String> elements, Snapshot s) {
        List<String> labels = elements.stream().map(e -> s.labelOf().getOrDefault(e, e)).toList();
        List<String> nodeIds = elements.stream().map(s.ownerOf()::get).filter(Objects::nonNull).distinct().toList();
        return new ValidationIssue(pattern.code(), pattern.code().severity(), pattern.message().apply(labels),
                nodeIds, elements, pattern.name());
    }
}
