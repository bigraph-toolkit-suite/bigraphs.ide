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
 * scratch (a schedule, e.g. {@code worklist-prefilter-seeded}) with continuing from the last fixpoint
 * ({@code incremental[-<schedule>]}: one coverage bigraph per mode, edited in place; see
 * {@link IncrementalCoverageAnalyzer}).
 * <p>
 * Fair by construction: every operation starts from the benchmark model. Before each operation (untimed,
 * {@link Level#Invocation}) the script undoes the previous chain and the incremental analyzer resyncs to the start
 * state; operation {@code k} then runs the edits of seed {@code 4711 + k}. So every analyzer sees the same
 * distribution of states, however many operations JMH fits into an iteration (a chain that just continued would drift
 * towards half of the relations removed and half of the children detached, and a fast analyzer would be measured on
 * a different, less covered model than a slow one). Every step includes the model edit, its events and the synced
 * bigraph, and the inspection asks for a fresh session. The incremental analyzer's first session (from scratch) is in
 * the untimed trial setup. Score = time per operation, i.e. per chain; divide by {@link #length} for one step.
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

    @Param({"worklist-prefilter-seeded", "incremental"})
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
    private boolean stateful;
    private int operation;
    private static final long SEED = 4711;

    @Setup(Level.Trial)
    public void setup() {
        Synced popp = new Synced();
        CoverageTestModels.FullModel trees = CoverageTestModels.fullModel(popp.model, depth, true);
        root = trees.root(NodeKind.PROBLEM);
        coverageMode = CoverageMode.valueOf(mode);
        edit = EditScript.Action.valueOf(action);
        coverage = CoverageTestModels.analyzer(analyzer, popp);
        stateful = coverage instanceof IncrementalCoverageAnalyzer;
        coverage.session(coverageMode).coverage(root); // the incremental analyzer's first fixpoint, untimed
        script = new EditScript(popp.model, SEED);
        operation = 0;
        System.out.printf("[edit-chain] variant=%s analyzer=%s mode=%s depth=%d action=%s length=%d popp nodes=%d rewriteKeepsLinks=%b%n",
                variant, analyzer, mode, depth, action, length, popp.model.getAllNodes().size(),
                ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS);
    }

    /** Back to the benchmark model, and the next seed (untimed). */
    @Setup(Level.Invocation)
    public void reset() {
        script.undo();
        if (stateful) coverage.session(coverageMode).coverage(root); // resync to the start state
        script.reseed(SEED + operation++);
    }

    @Benchmark
    public void editInspectChain(Blackhole bh) {
        for (int i = 0; i < length; i++) {
            script.step(edit);
            bh.consume(coverage.session(coverageMode).coverage(root));
        }
    }

    @TearDown(Level.Trial)
    public void report() {
        System.out.printf("[edit-chain] analyzer=%s depth=%d action=%s length=%d: %d operations (seeds %d..%d)%n",
                analyzer, depth, action, length, operation, SEED, SEED + operation - 1);
    }
}
