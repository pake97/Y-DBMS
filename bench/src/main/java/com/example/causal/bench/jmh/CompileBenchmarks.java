package com.example.causal.bench.jmh;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/** Snapshot build from an edge list (RQ2): with closures, and with the closure budget forcing the layer-pruned branch. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class CompileBenchmarks {

    @Param({"asia", "alarm"})
    public String dataset;

    /** closures: unlimited budget; pruned: causal.closures.budget=0. */
    @Param({"closures", "pruned"})
    public String branch;

    private EdgeList el;

    @Setup(Level.Trial)
    public void setup() {
        el = EdgeListLoader.load(dataset);
        if ("pruned".equals(branch)) System.setProperty("causal.closures.budget", "0");
        else System.clearProperty("causal.closures.budget");
    }

    @Benchmark
    public CompiledCausalGraph compile() {
        return SnapshotFactory.fromEdgeList(el);
    }
}
