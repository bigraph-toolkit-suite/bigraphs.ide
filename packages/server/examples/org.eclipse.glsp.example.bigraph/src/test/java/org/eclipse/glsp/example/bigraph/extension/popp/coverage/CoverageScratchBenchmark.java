package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH: one inspection from scratch ({@link BenchmarkSupport#inspect}: a session and the coverage of every node) per
 * operation, for a {@link BenchmarkConfigs configuration} on a benchmark model in a mode. The model, the analyzer and
 * its static analysis are built in the untimed setup, so the score is the latency of a session after any change for an
 * analyzer without incremental state.
 * <p>
 * Run with {@code org.openjdk.jmh.Main CoverageScratchBenchmark -p config=baseline,default -p depth=0,1,2,3}; see
 * {@code run-benchmarks.sh} for the parameters of every figure ({@code BenchmarkConfigs --list} prints the names of a
 * group, comma separated, for {@code -p}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class CoverageScratchBenchmark {

    /** A configuration name of {@link BenchmarkConfigs}. */
    @Param({"default"})
    public String config;

    /** Model depth: 6, 18, 42, 90 POPP nodes for 0 to 3. */
    @Param({"0", "1", "2", "3"})
    public int depth;

    /** A {@link CoverageMode} preset name. */
    @Param({"VERIFY"})
    public String mode;

    private CoverageAnalyzer analyzer;
    private CoverageMode coverageMode;
    private List<TreeNode<?>> nodes;

    @Setup(Level.Trial)
    public void setup() {
        if (!RewritingCoverageAnalyzer.REWRITING_SUPPORTED) throw new IllegalStateException("needs the patched framework");
        Synced popp = BenchmarkSupport.model(depth);
        nodes = BenchmarkSupport.nodes(popp);
        coverageMode = CoverageMode.valueOf(mode);
        Built built = new Built(BenchmarkConfigs.byName(config));
        analyzer = built.fromScratch(popp);
        BenchmarkSupport.check(config, popp, coverageMode, analyzer.session(coverageMode), "from scratch");
    }

    @Benchmark
    public int inspect() {
        return BenchmarkSupport.inspect(analyzer, coverageMode, nodes);
    }
}
