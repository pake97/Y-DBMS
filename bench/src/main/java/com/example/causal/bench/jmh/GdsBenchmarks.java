package com.example.causal.bench.jmh;

import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.db.GdsBaseline;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.QueryTimeout;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Tier (a) operators as GDS DFS/BFS over in-memory projections (baseline c); GDS has no path-role operator, 60 s per query. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class GdsBenchmarks {

    @Param({"asia", "alarm"})
    public String dataset;

    private EmbeddedNeo4j neo;
    private GdsBaseline gds;
    private Censor censor;
    private List<Workloads.Pair> pairs;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        neo = EmbeddedNeo4j.start(EdgeListLoader.load(dataset));
        gds = new GdsBaseline(neo);
        censor = new Censor("gds", dataset);
        pairs = Workloads.pairs(dataset);
    }

    @TearDown(Level.Trial)
    public void tearDown() { neo.close(); }

    private Workloads.Pair pair() {
        Workloads.Pair p = pairs.get(next);
        next = next + 1 == pairs.size() ? 0 : next + 1;
        return p;
    }

    private long run(String op, Workloads.Pair p, java.util.function.LongSupplier q) {
        return censor.record(op, p.t() + "->" + p.y(), censor.censored(op) ? QueryTimeout.TIMEOUT : q.getAsLong());
    }

    @Benchmark public long ancestors()   { Workloads.Pair p = pair(); return run("ancestors", p, () -> gds.ancestors(p.y())); }
    @Benchmark public long descendants() { Workloads.Pair p = pair(); return run("descendants", p, () -> gds.descendants(p.t())); }
    @Benchmark public long reaches()     { Workloads.Pair p = pair(); return run("reaches", p, () -> gds.reaches(p.t(), p.y())); }
}
