package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Config;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Before anything is measured: every {@link BenchmarkConfigs configuration} computes the coverage of the domain
 * analyzer, from scratch and incrementally, in every mode, after edits of every action, on the benchmark models up
 * to {@code maxDepth}. Fails with the first difference.
 * <ul>
 *   <li>{@code CoverageBenchmarkCheck [maxDepth=2] [configs=all] [edits=4]}: the check.</li>
 *   <li>{@code CoverageBenchmarkCheck --capabilities}: whether the framework on the classpath supports rewriting.</li>
 *   <li>{@code CoverageBenchmarkCheck --rules}: the rule counts of both gate rule sets.</li>
 *   <li>{@code CoverageBenchmarkCheck --list <names>}: the configurations of the groups or names, comma separated
 *   (for the {@code -p config=} parameter of the JMH benchmarks).</li>
 * </ul>
 */
public final class CoverageBenchmarkCheck {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--capabilities")) {
            System.out.println("rewrite=" + RewritingCoverageAnalyzer.REWRITING_SUPPORTED);
            return;
        }
        if (args.length > 0 && args[0].equals("--rules")) {
            for (BenchmarkConfigs.GateRules gateRules : BenchmarkConfigs.GateRules.values()) {
                List<CoverageRewriteRules.CompiledRule> rules = BenchmarkConfigs.ruleSet(gateRules).rules();
                System.out.printf("%s: %d rules (%d gate, %d lift, %d link)%n", gateRules.name().toLowerCase(),
                        rules.size(), count(rules, "gate"), count(rules, "lift"), count(rules, "link"));
            }
            return;
        }
        if (args.length > 1 && args[0].equals("--list")) {
            System.out.println(BenchmarkConfigs.named(args[1]).stream().map(Config::name).collect(Collectors.joining(",")));
            return;
        }
        int maxDepth = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        List<Config> configs = BenchmarkConfigs.named(args.length > 1 ? args[1] : "all");
        int edits = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        if (!RewritingCoverageAnalyzer.REWRITING_SUPPORTED) throw new IllegalStateException("needs the patched framework");
        int sessions = 0;
        for (Config config : configs) {
            Built built = new Built(config);
            long start = System.nanoTime();
            for (int depth = 0; depth <= maxDepth; depth++) {
                Synced popp = BenchmarkSupport.model(depth);
                CoverageAnalyzer scratch = built.fromScratch(popp);
                for (CoverageMode mode : CoverageMode.presets()) {
                    BenchmarkSupport.check(config.name(), popp, mode, scratch.session(mode), "from scratch, depth " + depth);
                    sessions++;
                }
                if (depth == 0) continue;
                for (EditScript.Action action : EditScript.Action.values()) {
                    for (CoverageMode mode : CoverageMode.presets()) {
                        Synced edited = BenchmarkSupport.model(depth);
                        CoverageAnalyzer incremental = built.incremental(edited);
                        incremental.session(mode);
                        EditScript script = new EditScript(edited.model, BenchmarkSupport.EDIT_SEED);
                        for (int edit = 0; edit < edits; edit++) {
                            script.undo();
                            incremental.session(mode);
                            script.reseed(BenchmarkSupport.EDIT_SEED + edit);
                            String what = script.step(action);
                            BenchmarkSupport.check(config.name(), edited, mode, incremental.session(mode),
                                    "incremental, depth " + depth + ", after " + what);
                            sessions++;
                        }
                    }
                }
            }
            System.out.printf("ok  %-34s (%.1f s)%n", config.name(), (System.nanoTime() - start) / 1e9);
        }
        System.out.printf("all %d configurations agree with the domain analyzer (%d sessions, depth <= %d)%n",
                configs.size(), sessions, maxDepth);
    }

    private static long count(List<CoverageRewriteRules.CompiledRule> rules, String prefix) {
        return rules.stream().filter(rule -> rule.name().startsWith(prefix)).count();
    }
}
