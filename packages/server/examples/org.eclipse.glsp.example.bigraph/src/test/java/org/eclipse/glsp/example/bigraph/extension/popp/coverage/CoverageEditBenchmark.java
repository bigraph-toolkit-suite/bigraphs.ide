package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH: edits, each followed by an inspection, as an editing user produces them. One operation is a fixed sequence of
 * {@link #edits} edits of {@link #action} (see {@link EditScript}), edit {@code k} with the seed {@code 4711 + k}, and
 * after each edit its undo: edit, inspection, undo, inspection. So one operation is {@code 2 * edits} edits with
 * their inspections ({@link BenchmarkSupport#inspect}), and every operation and every configuration does exactly the
 * same work. The score is the time of the whole sequence; the plots divide it by {@code 2 * edits}.
 * <p>
 * An edit includes the model's events, the synced bigraph and, for the incremental analyzer, its log.
 * {@link #analysis} {@code incremental} continues from the last fixpoint ({@link IncrementalRewritingCoverageAnalyzer}),
 * {@code scratch} computes every session from scratch ({@link RewritingCoverageAnalyzer}). The domain analyzer
 * computes every session from scratch either way. The incremental analyzer's first fixpoint is in the trial setup.
 * <p>
 * Why a fixed sequence: the edits differ in cost by an order of magnitude (a new link can raise half the model, a type
 * change one gate node). With one random edit per operation, JMH would fit more operations, and so other edits, into
 * the iterations of a fast configuration than of a slow one, and the means would compare different edit mixes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class CoverageEditBenchmark {

    /** A configuration name of {@link BenchmarkConfigs}. */
    @Param({"default"})
    public String config;

    /** {@code incremental} or {@code scratch}. */
    @Param({"incremental"})
    public String analysis;

    /** An {@link EditScript.Action}. */
    @Param({"MIXED"})
    public String action;

    /** Model depth: 18, 42, 90 POPP nodes for 1 to 3 (depth 0 has nothing to edit but relations). */
    @Param({"1", "2", "3"})
    public int depth;

    /** A {@link CoverageMode} preset name. */
    @Param({"VERIFY"})
    public String mode;

    /** The edits of one operation, each followed by its undo. */
    @Param({"6"})
    public int edits;

    private CoverageAnalyzer analyzer;
    private CoverageMode coverageMode;
    private EditScript script;
    private EditScript.Action edit;
    private List<TreeNode<?>> nodes;

    @Setup(Level.Trial)
    public void setup() {
        if (!RewritingCoverageAnalyzer.REWRITING_SUPPORTED) throw new IllegalStateException("needs the patched framework");
        Synced popp = BenchmarkSupport.model(depth);
        nodes = BenchmarkSupport.nodes(popp);
        coverageMode = CoverageMode.valueOf(mode);
        edit = EditScript.Action.valueOf(action);
        Built built = new Built(BenchmarkConfigs.byName(config));
        analyzer = switch (analysis) {
            case "incremental" -> built.incremental(popp);
            case "scratch" -> built.fromScratch(popp);
            default -> throw new IllegalArgumentException("analysis is incremental or scratch, not " + analysis);
        };
        analyzer.session(coverageMode); // the incremental analyzer's first fixpoint, untimed
        script = new EditScript(popp.model, BenchmarkSupport.EDIT_SEED);
        // the edits of the sequence, and their undos, checked against the domain analyzer
        for (int index = 0; index < edits; index++) {
            script.reseed(BenchmarkSupport.EDIT_SEED + index);
            String what = script.step(edit);
            BenchmarkSupport.check(config, popp, coverageMode, analyzer.session(coverageMode), "after " + what);
            script.undo();
            BenchmarkSupport.check(config, popp, coverageMode, analyzer.session(coverageMode), "after undoing " + what);
        }
    }

    @Benchmark
    public int editSequence() {
        int sum = 0;
        for (int index = 0; index < edits; index++) {
            script.reseed(BenchmarkSupport.EDIT_SEED + index);
            script.step(edit);
            sum += BenchmarkSupport.inspect(analyzer, coverageMode, nodes);
            script.undo();
            sum += BenchmarkSupport.inspect(analyzer, coverageMode, nodes);
        }
        return sum;
    }
}
