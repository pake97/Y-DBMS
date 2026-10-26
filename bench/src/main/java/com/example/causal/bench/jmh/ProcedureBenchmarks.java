package com.example.causal.bench.jmh;

import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.QueryTimeout;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tier (a) operators through the Y-DBMS procedures (CALL causal.* / causal.hasDirectedPath)
 * on the same embedded instance and workload as CypherBenchmarks: each query pays parsing,
 * planning, a transaction and result materialization, like the Cypher and GDS baselines.
 * Results are exhausted. Same 60 s transaction timeout as the baselines.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class ProcedureBenchmarks {

    public static final String ANCESTORS =
            "MATCH (v:Variable {id:$id}) CALL causal.ancestors([v]) YIELD variable RETURN variable.id";
    public static final String DESCENDANTS =
            "MATCH (v:Variable {id:$id}) CALL causal.descendants([v]) YIELD variable RETURN variable.id";
    public static final String REACHES =
            "MATCH (a:Variable {id:$u}), (b:Variable {id:$v}) RETURN causal.hasDirectedPath(a, b) AS r";
    public static final String CONFOUNDERS =
            "MATCH (a:Variable {id:$t}), (b:Variable {id:$y}) CALL causal.confounders(a, b) YIELD variable RETURN variable.id";
    public static final String MEDIATORS =
            "MATCH (a:Variable {id:$t}), (b:Variable {id:$y}) CALL causal.mediators(a, b) YIELD variable RETURN variable.id";
    // Id-returning variants: same answers, one `id` column, no node binding. reaches has none
    // (causal.hasDirectedPath already returns a boolean).
    public static final String ANCESTOR_IDS =
            "MATCH (v:Variable {id:$id}) CALL causal.ancestorIds([v]) YIELD id RETURN id";
    public static final String DESCENDANT_IDS =
            "MATCH (v:Variable {id:$id}) CALL causal.descendantIds([v]) YIELD id RETURN id";
    public static final String CONFOUNDER_IDS =
            "MATCH (a:Variable {id:$t}), (b:Variable {id:$y}) CALL causal.confounderIds(a, b) YIELD id RETURN id";
    public static final String MEDIATOR_IDS =
            "MATCH (a:Variable {id:$t}), (b:Variable {id:$y}) CALL causal.mediatorIds(a, b) YIELD id RETURN id";

    @Param({"asia", "alarm"})
    public String dataset;

    private EmbeddedNeo4j neo;
    private Censor censor;
    private List<Workloads.Pair> pairs;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        neo = EmbeddedNeo4j.start(EdgeListLoader.load(dataset));
        censor = new Censor("procedure", dataset);
        pairs = Workloads.pairs(dataset);
    }

    @TearDown(Level.Trial)
    public void tearDown() { neo.close(); }

    private Workloads.Pair pair() {
        Workloads.Pair p = pairs.get(next);
        next = next + 1 == pairs.size() ? 0 : next + 1;
        return p;
    }

    private long count(String op, Workloads.Pair p, String cypher, Map<String, Object> params) {
        return censor.record(op, p.t() + "->" + p.y(),
                censor.censored(op) ? QueryTimeout.TIMEOUT : QueryTimeout.count(neo.db(), cypher, params));
    }

    @Benchmark public long ancestors()   { Workloads.Pair p = pair(); return count("ancestors", p, ANCESTORS, Map.of("id", p.y())); }
    @Benchmark public long descendants() { Workloads.Pair p = pair(); return count("descendants", p, DESCENDANTS, Map.of("id", p.t())); }
    @Benchmark public long reaches()     { Workloads.Pair p = pair(); return count("reaches", p, REACHES, Map.of("u", p.t(), "v", p.y())); }
    @Benchmark public long confounders() { Workloads.Pair p = pair(); return count("confounders", p, CONFOUNDERS, Map.of("t", p.t(), "y", p.y())); }
    @Benchmark public long mediators()   { Workloads.Pair p = pair(); return count("mediators", p, MEDIATORS, Map.of("t", p.t(), "y", p.y())); }

    @Benchmark public long ancestorsIds()   { Workloads.Pair p = pair(); return count("ancestorsIds", p, ANCESTOR_IDS, Map.of("id", p.y())); }
    @Benchmark public long descendantsIds() { Workloads.Pair p = pair(); return count("descendantsIds", p, DESCENDANT_IDS, Map.of("id", p.t())); }
    @Benchmark public long confoundersIds() { Workloads.Pair p = pair(); return count("confoundersIds", p, CONFOUNDER_IDS, Map.of("t", p.t(), "y", p.y())); }
    @Benchmark public long mediatorsIds()   { Workloads.Pair p = pair(); return count("mediatorsIds", p, MEDIATOR_IDS, Map.of("t", p.t(), "y", p.y())); }

    /** Answer of one procedure query as an int (size, or 1/0 for reaches), for the agreement test. */
    public static long answer(EmbeddedNeo4j neo, String cypher, Map<String, Object> params) {
        if (cypher.equals(REACHES)) {
            Object r = QueryTimeout.single(neo.db(), cypher, params);
            return Boolean.TRUE.equals(r) ? 1 : 0;
        }
        return QueryTimeout.count(neo.db(), cypher, params);
    }
}
