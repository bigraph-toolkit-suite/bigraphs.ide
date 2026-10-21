package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Chains of edits, each followed by an inspection, as an editing user produces them: one operation is {@link #length}
 * steps of "edit ({@link #action}), then the coverage of the problem root". Compares recomputing every session from
 * scratch ({@code workset-prefilter-seeded}, the default analyzer) with continuing from the last fixpoint
 * ({@code incremental}: one coverage bigraph per mode, edited in place; see {@link IncrementalCoverageAnalyzer}).
 * <p>
 * Fair by construction: every analyzer starts from the same model with the same edit script (same seed), every step
 * includes the model edit, its events and the synced bigraph, and the inspection asks for a fresh session. The edits
 * are toggles ({@link EditScript}), so the model stays near the benchmark model however many operations JMH runs.
 * The incremental analyzer's first session (from scratch) is in the untimed setup. Score = time per operation, i.e.
 * per chain; divide by {@link #length} for one step.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class EditChainBenchmark {

    @Param({"1", "2", "3"})
    public int depth;

    @Param({"workset-prefilter-seeded", "incremental"})
    public String analyzer;

    /** An {@link EditScript.Action}. */
    @Param({"LINK", "TYPE", "MOVE", "NODE", "MIXED"})
    public String action;

    /** Steps (edit + inspect) per operation. */
    @Param({"1", "10"})
    public int length;

    @Param({"VERIFY"})
    public String mode;

    public String variant = System.getProperty("variant", "unlabelled");

    private CoverageAnalyzer coverage;
    private EditScript script;
    private EditScript.Action edit;
    private CoverageMode coverageMode;
    private TreeNode<?> root;

    @Setup(Level.Trial)
    public void setup() {
        Synced popp = new Synced();
        CoverageTestModels.FullModel trees = CoverageTestModels.fullModel(popp.model, depth, true);
        root = trees.root(NodeKind.PROBLEM);
        coverageMode = CoverageMode.valueOf(mode);
        edit = EditScript.Action.valueOf(action);
        coverage = CoverageTestModels.analyzer(analyzer, popp);
        coverage.session(coverageMode).coverage(root); // the incremental analyzer's first fixpoint, untimed
        script = new EditScript(popp.model, 4711);
        System.out.printf("[edit-chain] variant=%s analyzer=%s mode=%s depth=%d action=%s length=%d popp nodes=%d rewriteKeepsLinks=%b%n",
                variant, analyzer, mode, depth, action, length, popp.model.getAllNodes().size(),
                ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS);
    }

    @Benchmark
    public void editInspectChain(Blackhole bh) {
        for (int i = 0; i < length; i++) {
            script.step(edit);
            bh.consume(coverage.session(coverageMode).coverage(root));
        }
    }
}
