package com.example.causal.bench.jmh;

import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.db.CypherBaseline;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.QueryTimeout;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Tier (a) operators as Cypher variable-length patterns in embedded Neo4j (baseline b), 60 s per query. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class CypherBenchmarks {

    @Param({"asia", "alarm"})
    public String dataset;

    private EmbeddedNeo4j neo;
    private CypherBaseline cypher;
    private Censor censor;
    private List<Workloads.Pair> pairs;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        neo = EmbeddedNeo4j.start(EdgeListLoader.load(dataset));
        cypher = new CypherBaseline(neo.db());
        censor = new Censor("cypher", dataset);
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

    @Benchmark public long ancestors()   { Workloads.Pair p = pair(); return run("ancestors", p, () -> cypher.ancestors(p.y())); }
    @Benchmark public long descendants() { Workloads.Pair p = pair(); return run("descendants", p, () -> cypher.descendants(p.t())); }
    @Benchmark public long reaches()     { Workloads.Pair p = pair(); return run("reaches", p, () -> cypher.reaches(p.t(), p.y())); }
    @Benchmark public long confounders() { Workloads.Pair p = pair(); return run("confounders", p, () -> cypher.confounders(p.t(), p.y())); }
    @Benchmark public long mediators()   { Workloads.Pair p = pair(); return run("mediators", p, () -> cypher.mediators(p.t(), p.y())); }
}
