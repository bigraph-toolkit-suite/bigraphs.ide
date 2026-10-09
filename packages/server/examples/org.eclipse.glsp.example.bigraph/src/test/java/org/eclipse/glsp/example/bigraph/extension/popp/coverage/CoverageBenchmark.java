package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark of the coverage analyzers. It only uses API that is stable across the commits being compared
 * ({@code new RewritingCoverageAnalyzer(Supplier<POPPBigraph>, RelationGraph)}, {@code new
 * DomainCoverageAnalyzer(RelationGraph)}, {@link CoverageAnalyzer}), so the same file can be dropped into any
 * checkout. Name the checkout with {@code -Dvariant=<label>} (see {@link #variant}); the label ends up in the output.
 * <p>
 * Every benchmark does the same observable work for both analyzers: a fresh session, then an answer.
 * Model construction is in the (untimed) setup. The two edit benchmarks do time the edits themselves, which include
 * the model's events and the synced {@code POPPBigraph}; that cost is the same for every analyzer, see
 * {@link #editOnly()}.
 * <p>
 * Run it with {@code org.openjdk.jmh.Main}, e.g.
 * {@code ... org.openjdk.jmh.Main CoverageBenchmark -rf json -rff domain.json -p analyzer=domain}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class CoverageBenchmark {

    /** 2^depth leaves; override with {@code -p depth=1,2} for slow variants. */
    @Param({"1", "2", "3", "4"})
    public int depth;

    /**
     * "domain"; "rewriting-naive", "rewriting-census" and "rewriting" are {@code RewritingCoverageAnalyzer} without
     * pruning, with the census only, and with census and link guard; "fragment" is
     * {@code FragmentRewritingCoverageAnalyzer}, and "fragment-rounds", "fragment-pruned", "fragment-worklist",
     * "fragment-slim", "fragment-memo" switch its levers one at a time (see {@code CoverageTestModels#analyzer}).
     */
    @Param({"domain", "rewriting"})
    public String analyzer;

    @Param({"VERIFY"})
    public CoverageMode mode;

    /** Free-form label for the checkout under test, copied from {@code -Dvariant}. */
    public String variant = System.getProperty("variant", "unlabelled");

    private POPPModel model;
    private Tree tree;
    private CoverageAnalyzer coverage;
    private List<TreeNode<?>> nodes;

    @Setup(Level.Trial)
    public void setup() {
        Synced popp = new Synced();
        this.model = popp.model;
        this.tree = CoverageTestModels.balancedTree(model, depth, true);
        this.nodes = new ArrayList<>(model.getAllNodes());
        this.coverage = CoverageTestModels.analyzer(analyzer, popp);
        System.out.printf("[benchmark] variant=%s analyzer=%s depth=%d popp nodes=%d%n", variant, analyzer, depth, nodes.size());
    }

    /** Inspect one node: the "click a node, see its coverage" action. */
    @Benchmark
    public Coverage inspectRoot() {
        return coverage.session(mode).coverage(tree.root());
    }

    /** Inspect every node: what an overlay, report or gap highlight needs. */
    @Benchmark
    public void inspectAllNodes(Blackhole bh) {
        CoverageSession session = coverage.session(mode);
        for (TreeNode<?> node : nodes) bh.consume(session.coverage(node));
    }

    /**
     * Edit a relation, inspect, undo the edit: what a user action costs end to end (the undo is part of the sample).
     * The cost of the coverage layer alone is {@code editThenInspect - editOnly}, taken at the same depth.
     */
    @Benchmark
    public Coverage editThenInspect() {
        SuccessCriteria leaf = tree.leaves().getFirst();
        SuccessProof proof = tree.proofs().getFirst();
        model.unrelate(leaf, proof);
        Coverage result = coverage.session(mode).coverage(tree.root());
        model.relate(leaf, proof);
        return result;
    }

    /**
     * The same two edits as {@link #editThenInspect()} without any coverage query: the baseline to subtract. It does
     * not touch the analyzer, so run it once (with {@code -p analyzer=domain}); a second run with "rewriting" only
     * measures the same thing again.
     */
    @Benchmark
    public void editOnly() {
        model.unrelate(tree.leaves().getFirst(), tree.proofs().getFirst());
        model.relate(tree.leaves().getFirst(), tree.proofs().getFirst());
    }

    /**
     * The first coverage session in a fresh JVM, after the model and the analyzer have been constructed in setup:
     * class loading, JIT and lazy initialisation of the coverage code. It does not include loading the model and
     * bigraph classes, which setup already did, so it is slightly less than the first request after a server start.
     * One sample per fork.
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    @Fork(20)
    public Coverage coldFirstSession() {
        return coverage.session(mode).coverage(tree.root());
    }
}