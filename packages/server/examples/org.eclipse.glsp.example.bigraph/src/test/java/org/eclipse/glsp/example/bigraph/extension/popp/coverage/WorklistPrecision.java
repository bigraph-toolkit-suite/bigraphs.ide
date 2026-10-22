package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * How precise is the rule worklist? Runs traced sessions from scratch with {@code worklist-seeded} (every queued rule is
 * matched, so the trace shows the whole queue) and splits the queued rule calls after the seed wave into:
 * <ul>
 *   <li><b>hits</b>: rules that rewrote something in their wave;</li>
 *   <li><b>rule-level waste</b>: queued because some hit enables them statically, but no hit of an enabling rule was
 *   ever followed by a hit of this rule in the next wave, in any session of the run. A dependency analysis exact at
 *   the rule level (structure, not location) could at best drop these;</li>
 *   <li><b>location waste</b>: the rest, queued through an enabling pair that does occur, but without a match this time
 *   (the hit was elsewhere in the model). Only queueing per location (semi-naive: rule plus where the change was) could
 *   drop these.</li>
 * </ul>
 * The observed pairs are a subset of the true dependencies, so the rule-level share is an upper bound of what a better
 * static analysis can save. Also reports per enabling pair how often it was used.
 * <p>
 * {@code WorklistPrecision <out-dir> [depths=0,1,2] [complete=true,false]}
 */
public final class WorklistPrecision {

    private record Session(String label, int nodes, List<Set<String>> queued, List<Set<String>> hits) {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "worklist-precision");
        int[] depths = Arrays.stream((args.length > 1 ? args[1] : "0,1,2").split(",")).mapToInt(Integer::parseInt).toArray();
        String[] completes = (args.length > 2 ? args[2] : "true,false").split(",");
        Files.createDirectories(out);
        Map<String, List<String>> enables = ScheduledRewritingCoverageAnalyzer.enablingGraph();

        List<Session> sessions = new ArrayList<>();
        for (int depth : depths) {
            for (String complete : completes) {
                for (CoverageMode mode : CoverageMode.presets()) {
                    Synced popp = new Synced();
                    var trees = CoverageTestModels.fullModel(popp.model, depth, Boolean.parseBoolean(complete));
                    var analyzer = new ScheduledRewritingCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph),
                            ScheduledRewritingCoverageAnalyzer.Options.WORKLIST_SEEDED);
                    analyzer.setTracing(true);
                    analyzer.session(mode).coverage(trees.root(NodeKind.PROBLEM));
                    RewritingTrace trace = analyzer.lastTrace();
                    int waves = trace.rounds().size();
                    List<Set<String>> queued = new ArrayList<>(), hits = new ArrayList<>();
                    for (int w = 0; w < waves; w++) {
                        queued.add(new LinkedHashSet<>());
                        hits.add(new LinkedHashSet<>());
                    }
                    for (RewritingTrace.Call c : trace.calls()) {
                        if (c.kind() != RewritingTrace.Kind.RULE) continue;
                        queued.get(c.round() - 1).add(c.name());
                        if (c.hit()) hits.get(c.round() - 1).add(c.name());
                    }
                    sessions.add(new Session(String.format("depth=%d complete=%s %s", depth, complete, mode.name()),
                            popp.model.getAllNodes().size(), queued, hits));
                }
            }
        }

        // enabling pairs that were used: r enables t, r hit, and t hit later (in the next wave, or later in the same
        // wave: a rewrite is visible to the rules after it in the wave)
        Map<String, Integer> used = new TreeMap<>();
        for (Session s : sessions) {
            for (int w = 0; w < s.hits.size(); w++) {
                List<String> here = new ArrayList<>(s.hits.get(w));   // in call order
                for (int i = 0; i < here.size(); i++) {
                    String r = here.get(i);
                    for (String t : enables.getOrDefault(r, List.of())) {
                        boolean later = here.subList(i + 1, here.size()).contains(t)
                                || (w + 1 < s.hits.size() && s.hits.get(w + 1).contains(t));
                        if (later) used.merge(r + " -> " + t, 1, Integer::sum);
                    }
                }
            }
        }

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("worklist-precision.csv")))) {
            csv.println("session,popp_nodes,waves,queued_after_seed,hits,rule_level_waste,location_waste");
            long tq = 0, th = 0, tr = 0, tl = 0;
            System.out.printf("%-42s %5s %5s %8s %6s %10s %10s%n", "session", "nodes", "waves", "queued", "hits", "rule-level", "location");
            for (Session s : sessions) {
                int q = 0, h = 0, rule = 0, loc = 0;
                for (int w = 1; w < s.queued.size(); w++) {
                    Set<String> hitBefore = s.hits.get(w - 1);
                    for (String t : s.queued.get(w)) {
                        q++;
                        if (s.hits.get(w).contains(t)) { h++; continue; }
                        boolean viaUsedPair = hitBefore.stream().anyMatch(r -> used.containsKey(r + " -> " + t));
                        if (viaUsedPair) loc++; else rule++;
                    }
                }
                tq += q; th += h; tr += rule; tl += loc;
                csv.printf("%s,%d,%d,%d,%d,%d,%d%n", s.label, s.nodes, s.queued.size(), q, h, rule, loc);
                System.out.printf("%-42s %5d %5d %8d %6d %10d %10d%n", s.label, s.nodes, s.queued.size(), q, h, rule, loc);
            }
            System.out.printf("%-42s %5s %5s %8d %6d %10d %10d%n", "total", "", "", tq, th, tr, tl);
            System.out.printf("of the queued calls after the seed: hits %.1f%%, rule-level waste %.1f%%, location waste %.1f%%%n",
                    100.0 * th / tq, 100.0 * tr / tq, 100.0 * tl / tq);
        }

        int staticPairs = enables.values().stream().mapToInt(List::size).sum();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("enabling-pairs.csv")))) {
            csv.println("pair,used");
            for (var e : enables.entrySet()) {
                for (String t : e.getValue()) {
                    String p = e.getKey() + " -> " + t;
                    csv.printf("\"%s\",%d%n", p, used.getOrDefault(p, 0));
                }
            }
        }
        System.out.printf("static enabling pairs: %d, of which used in some session: %d (%.1f%%)%n",
                staticPairs, used.size(), 100.0 * used.size() / staticPairs);
    }
}
