package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.FullModel;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark of the coverage analyzers on {@link CoverageTestModels#fullModel}: all six trees balanced, depth
 * {@link #depth} (6, 18, 42, 90 POPP nodes for depth 0..3), leaves connected tree to tree. Name the run with
 * {@code -Dvariant=<label>}; it ends up in the output.
 * <p>
 * Every benchmark does the same observable work for each analyzer: a fresh session, then an answer. Model
 * construction is in the (untimed) setup. {@link #editThenInspect()} also times one relation edit and its undo,
 * including the model's events and the synced bigraph; subtract {@link #editOnly()} for the coverage share.
 * <p>
 * Run it with {@code org.openjdk.jmh.Main}, e.g. {@code ... org.openjdk.jmh.Main CoverageBenchmark -rf json -rff
 * out.json -p analyzer=domain,worklist-guards-seeded-first -p depth=0,1,2,3}; see {@code run-benchmarks.sh} for the whole set. The
 * whole-agent analyzers ({@code rewriting*}, {@code guards}, {@code worklist*}) take seconds per session from depth 2
 * on, so give them fewer depths.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class CoverageBenchmark {

    /** Depth of every tree: 2^depth leaves each, 6 * (2^(depth+1) - 1) POPP nodes. */
    @Param({"0", "1", "2", "3"})
    public int depth;

    /** A name accepted by {@code CoverageTestModels.analyzer}. */
    @Param({"domain", "worklist-guards-seeded-first"})
    public String analyzer;

    /** A {@link CoverageMode} preset name. */
    @Param({"VERIFY"})
    public String mode;

    /** {@code false}: only every other criterion is validated, so coverage stays partial. */
    @Param({"true"})
    public boolean complete;

    /** Free-form label for the run, copied from {@code -Dvariant}. */
    public String variant = System.getProperty("variant", "unlabelled");

    private POPPModel model;
    private FullModel trees;
    private CoverageMode coverageMode;
    private CoverageAnalyzer coverage;
    private List<TreeNode<?>> nodes;

    @Setup(Level.Trial)
    public void setup() {
        Synced popp = new Synced();
        this.model = popp.model;
        this.trees = CoverageTestModels.fullModel(model, depth, complete);
        this.nodes = new ArrayList<>(model.getAllNodes());
        this.coverageMode = CoverageMode.valueOf(mode);
        this.coverage = CoverageTestModels.analyzer(analyzer, popp);
        // the oracle records its schedule outside the measurement (only meaningful with inspectRoot)
        if (coverage instanceof OracleRewritingCoverageAnalyzer oracle) oracle.prepare(coverageMode);
        System.out.printf("[benchmark] variant=%s analyzer=%s mode=%s depth=%d complete=%b popp nodes=%d matchFirst=%b rewriteKeepsLinks=%b%n",
                variant, analyzer, mode, depth, complete, nodes.size(), ScheduledRewritingCoverageAnalyzer.FIRST_MATCH_WORKS,
                ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS);
    }

    /** Inspect the problem root: the "click a node, see its coverage" action. */
    @Benchmark
    public Coverage inspectRoot() {
        return coverage.session(coverageMode).coverage(trees.root(NodeKind.PROBLEM));
    }

    /** Inspect every node: what an overlay, report or gap highlight needs. */
    @Benchmark
    public void inspectAllNodes(Blackhole bh) {
        CoverageSession session = coverage.session(coverageMode);
        for (TreeNode<?> node : nodes) bh.consume(session.coverage(node));
    }

    /**
     * Remove a validates relation, inspect, put it back: what a user action costs end to end (the undo is part of the
     * sample). The coverage share is {@code editThenInspect - editOnly}, at the same depth. Not meaningful for the
     * incremental analyzer: the undo and the next sample's edit cancel out, so it would only sync two operations onto an
     * unchanged fixpoint; its edit costs are measured by {@link EditChainBenchmark}.
     */
    @Benchmark
    public Coverage editThenInspect() {
        TreeNode<?> proof = trees.leaf(NodeKind.SUCCESS_PROOF, 0), criterion = trees.leaf(NodeKind.SUCCESS_CRITERIA, 0);
        model.unrelate(proof, criterion);
        Coverage result = coverage.session(coverageMode).coverage(trees.root(NodeKind.PROBLEM));
        model.relate(proof, criterion);
        return result;
    }

    /** The same two edits as {@link #editThenInspect()} without a coverage query; independent of the analyzer. */
    @Benchmark
    public void editOnly() {
        TreeNode<?> proof = trees.leaf(NodeKind.SUCCESS_PROOF, 0), criterion = trees.leaf(NodeKind.SUCCESS_CRITERIA, 0);
        model.unrelate(proof, criterion);
        model.relate(proof, criterion);
    }

    /**
     * The first coverage session in a fresh JVM, after model and analyzer construction in setup: class loading, JIT
     * and lazy initialisation of the coverage code. One sample per fork.
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    @Fork(20)
    public Coverage coldFirstSession() {
        return coverage.session(coverageMode).coverage(trees.root(NodeKind.PROBLEM));
    }
}
