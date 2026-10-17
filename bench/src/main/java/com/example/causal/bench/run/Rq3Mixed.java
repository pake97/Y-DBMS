package com.example.causal.bench.run;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.db.EmbeddedNeo4j;
import com.example.causal.bench.db.QueryTimeout;
import com.example.causal.bench.jmh.ProcedureBenchmarks;
import com.example.causal.runtime.RuntimeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * RQ3 mixed workload: in embedded Neo4j with the change listener active, {@value #QUERIES}
 * procedure queries (uniform over ancestors, reaches, confounders, mediators and the canonical
 * adjustment decision, on the dataset's pairs) with one edit every {@code k} queries. An edit is
 * one transaction flipping the {@code active} flag of a random original edge (off, or back on),
 * so the graph stays a DAG. Policies: {@code patch} (default: the listener patches the snapshot
 * inside the edit's commit) and {@code recompile} ({@code -Dcausal.patch.enabled=false}: the
 * listener invalidates). Whenever an edit leaves no snapshot (always under {@code recompile}; under
 * {@code patch} when the patch declines, e.g. deletion cones over {@code causal.patch.threshold}),
 * the next query first runs {@code CALL causal.compile()}, charged to that query, and the run
 * counts it in {@code recompiles}. Query latency is client-side wall time; the edit transactions are
 * timed separately. Policy (iii), external re-export, is composed in results_to_tex from the
 * Phase 1 pgmpy round trip.
 *
 * <p>usage: Rq3Mixed &lt;out.csv&gt; &lt;dataset,dataset,...&gt; [k,k,...] (default 1,10,100). Appends rows.
 */
public final class Rq3Mixed {

    static final int QUERIES = 10_000, WARMUP = 1_000;
    static final String DECIDE = "MATCH (a:Variable {id:$t}), (b:Variable {id:$y}) "
            + "CALL causal.adjustmentSets(a, b, 'canonical', 1) YIELD identifiable RETURN identifiable";
    static final String FLIP = "MATCH (:Variable {id:$f})-[r:CAUSES]->(:Variable {id:$t}) SET r.active = $on";
    static final String[] OPS = {"ancestors", "reaches", "confounders", "mediators", "decide"};

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        int[] ks = Arrays.stream((args.length > 2 ? args[2] : "1,10,100").split(",")).mapToInt(Integer::parseInt).toArray();
        if (!Files.exists(out)) Files.writeString(out, "dataset,policy,k,queries,edits,recompiles,median_us,p99_us,edit_median_us,edit_p99_us\n");
        for (String ds : args[1].split(",")) {
            EdgeList el = EdgeListLoader.load(ds);
            List<Workloads.Pair> pairs = Workloads.pairs(ds);
            try (EmbeddedNeo4j neo = EmbeddedNeo4j.start(el)) {
                for (String policy : List.of("patch", "recompile")) {
                    for (int k : ks) {
                        String row = run(neo, el, pairs, policy, k);
                        Files.writeString(out, row, StandardOpenOption.APPEND);
                        System.out.print("rq3 mixed " + row);
                    }
                }
            } finally {
                System.clearProperty("causal.patch.enabled");
            }
        }
    }

    private static String run(EmbeddedNeo4j neo, EdgeList el, List<Workloads.Pair> pairs, String policy, int k) {
        System.setProperty("causal.patch.enabled", String.valueOf("patch".equals(policy)));
        Random rnd = new Random(Objects.hash(el.dataset(), policy, k));
        List<int[]> on = new ArrayList<>(Arrays.asList(el.edges())), off = new ArrayList<>();
        neo.run("CALL causal.compile()", Map.of());
        long[] query = new long[QUERIES];
        List<Long> edits = new ArrayList<>();
        boolean stale = false;
        int recompiles = 0;
        for (int q = -WARMUP; q < QUERIES; q++) {
            if (q % k == 0) {
                boolean turnOn = !off.isEmpty() && (on.isEmpty() || rnd.nextBoolean());
                List<int[]> from = turnOn ? off : on, to = turnOn ? on : off;
                int[] e = from.remove(rnd.nextInt(from.size()));
                to.add(e);
                long s = System.nanoTime();
                neo.run(FLIP, Map.of("f", el.ids().get(e[0]), "t", el.ids().get(e[1]), "on", turnOn));
                if (q >= 0) edits.add(System.nanoTime() - s);
                stale = RuntimeRegistry.getInstance().currentSnapshot() == null;
            }
            Workloads.Pair p = pairs.get(rnd.nextInt(pairs.size()));
            String op = OPS[rnd.nextInt(OPS.length)];
            long s = System.nanoTime();
            if (stale) {
                neo.run("CALL causal.compile()", Map.of());
                stale = false;
                if (q >= 0) recompiles++;
            }
            query(neo, op, p);
            if (q >= 0) query[q] = System.nanoTime() - s;
        }
        for (int[] e : off) neo.run(FLIP, Map.of("f", el.ids().get(e[0]), "t", el.ids().get(e[1]), "on", true));
        Arrays.sort(query);
        long[] ed = edits.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n", el.dataset(), policy, k, QUERIES, ed.length, recompiles,
                pct(query, 0.5), pct(query, 0.99), pct(ed, 0.5), pct(ed, 0.99));
    }

    private static void query(EmbeddedNeo4j neo, String op, Workloads.Pair p) {
        Map<String, Object> ty = Map.of("t", p.t(), "y", p.y());
        long r = switch (op) {
            case "ancestors" -> QueryTimeout.count(neo.db(), ProcedureBenchmarks.ANCESTOR_IDS, Map.of("id", p.y()));
            case "reaches" -> QueryTimeout.count(neo.db(), ProcedureBenchmarks.REACHES, Map.of("u", p.t(), "v", p.y()));
            case "confounders" -> QueryTimeout.count(neo.db(), ProcedureBenchmarks.CONFOUNDER_IDS, ty);
            case "mediators" -> QueryTimeout.count(neo.db(), ProcedureBenchmarks.MEDIATOR_IDS, ty);
            default -> QueryTimeout.count(neo.db(), DECIDE, ty);
        };
        if (r == QueryTimeout.TIMEOUT) throw new IllegalStateException(op + " timed out on " + p);
    }

    /** Percentile of sorted nanoseconds, in µs (NaN if empty). */
    private static double pct(long[] sorted, double q) {
        return sorted.length == 0 ? Double.NaN : sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))] / 1e3;
    }
}
