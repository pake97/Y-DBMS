package com.example.causal.bench.jmh;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.algorithms.PathAnalyzer;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tier (a) operators, index vs DFS baseline (RQ1, RQ4). One workload pair per
 * invocation, cycling through the dataset's pairs.csv. Datasets are passed with
 * {@code -p dataset=a,b,...} by the scripts; the default is a smoke subset.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class TierABenchmarks {

    @Param({"asia", "alarm"})
    public String dataset;

    /** on: closures; off: -Dcausal.index=off (per-query DFS over the id maps). */
    @Param({"on", "off"})
    public String index;

    private ReachabilityIndex reach;
    private PathAnalyzer paths;
    private List<Workloads.Pair> pairs;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        EdgeList el = EdgeListLoader.load(dataset);
        CompiledCausalGraph g = SnapshotFactory.fromEdgeList(el);
        if ("off".equals(index)) System.setProperty("causal.index", "off");
        else System.clearProperty("causal.index");
        reach = new ReachabilityIndex(g);
        paths = new PathAnalyzer(g);
        pairs = Workloads.pairs(dataset);
    }

    private Workloads.Pair pair() {
        Workloads.Pair p = pairs.get(next);
        next = next + 1 == pairs.size() ? 0 : next + 1;
        return p;
    }

    @Benchmark public Set<String> ancestors()   { return reach.ancestors(pair().y()); }
    @Benchmark public Set<String> descendants() { return reach.descendants(pair().t()); }
    @Benchmark public boolean reaches()         { Workloads.Pair p = pair(); return reach.hasDirectedPath(p.t(), p.y()); }
    @Benchmark public Set<String> confounders() { Workloads.Pair p = pair(); return paths.confounders(p.t(), p.y()); }
    @Benchmark public Set<String> mediators()   { Workloads.Pair p = pair(); return paths.mediators(p.t(), p.y()); }
}
