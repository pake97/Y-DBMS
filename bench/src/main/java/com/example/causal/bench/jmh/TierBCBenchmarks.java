package com.example.causal.bench.jmh;

import com.example.causal.bench.baseline.DSeparationReference;
import com.example.causal.bench.baseline.LegacyEnumeration;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.db.QueryTimeout;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.algorithms.Adjustment;
import com.example.causal.runtime.algorithms.DSeparation;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Tiers (b) and (c) (RQ1, RQ4): d-separation over triples_balanced.csv, identification over
 * pairs.csv, one workload item per invocation, rotating. Datasets come from {@code -p dataset=}.
 *
 * <ul>
 *   <li>{@code dsep}: {@code DSeparation.separated} with {@code causal.dsep.mask} = {@code mask}
 *       (set once per fork), over the triples whose pgmpy verdict is {@code verdict}, so the
 *       separated and connected medians are separate JMH results;</li>
 *   <li>{@code dsepReference}: the July Bayes-Ball over {@code Set<String>} on the same triples;</li>
 *   <li>{@code decide}, {@code findMinimal}, {@code listMinimal100} ({@code limit = 100}, returns
 *       the number of sets emitted);</li>
 *   <li>{@code decideReference}: {@link LegacyEnumeration}. In the trial setup every pair runs once
 *       under the 60 s cap; a timed-out pair is censored ({@link Censor}: after 3 timeouts the rest
 *       of the dataset's pairs are skipped and recorded as censored) and only finished pairs are
 *       sampled. The setup then warms both for ~1 s and writes per-pair legacy and {@code decide} medians to
 *       {@code legacy_pairs.csv} next to {@code -Dbench.timeouts}, with {@code k}, the size of the
 *       smallest set the legacy found ({@code none}: no backdoor set), for the stratified table and
 *       {@code \speedupDecideVsEnumeration} (a median over pairs, which the rotating JMH
 *       histogram cannot give).</li>
 * </ul>
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class TierBCBenchmarks {

    /** Snapshot and pairs of one dataset. */
    @State(Scope.Benchmark)
    public static class Data {
        @Param({"asia", "alarm"})
        public String dataset;

        CompiledCausalGraph g;
        List<Workloads.Pair> pairs;
        int[] t, y;

        @Setup(Level.Trial)
        public void setup() {
            g = SnapshotFactory.fromEdgeList(EdgeListLoader.load(dataset));
            pairs = Workloads.pairs(dataset);
            t = new int[pairs.size()];
            y = new int[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                t[i] = g.indexOf(pairs.get(i).t());
                y[i] = g.indexOf(pairs.get(i).y());
            }
        }
    }

    /** The balanced triples with one verdict, as bitsets and as id sets. */
    @State(Scope.Thread)
    public static class Triples {
        @Param({"separated", "connected"})
        public String verdict;

        long[][] x, y, z;
        List<Set<String>> xs = new ArrayList<>(), ys = new ArrayList<>(), zs = new ArrayList<>();
        int next;

        @Setup(Level.Trial)
        public void setup(Data d) {
            boolean want = "separated".equals(verdict);
            List<Workloads.Triple> sel = Workloads.balancedTriples(d.dataset).stream()
                    .filter(l -> l.separated() == want).map(Workloads.Labeled::triple).toList();
            x = new long[sel.size()][];
            y = new long[sel.size()][];
            z = new long[sel.size()][];
            for (int i = 0; i < sel.size(); i++) {
                Workloads.Triple tr = sel.get(i);
                x[i] = DSeparation.bits(d.g, d.g.indexOf(tr.x()));
                y[i] = DSeparation.bits(d.g, d.g.indexOf(tr.y()));
                z[i] = DSeparation.bits(d.g, tr.z().stream().mapToInt(d.g::indexOf).toArray());
                xs.add(Set.of(tr.x()));
                ys.add(Set.of(tr.y()));
                zs.add(Set.copyOf(tr.z()));
            }
        }

        int next() {
            int i = next;
            next = next + 1 == x.length ? 0 : next + 1;
            return i;
        }
    }

    /** causal.dsep.mask for this fork (DSeparation reads it per call). */
    @State(Scope.Benchmark)
    public static class Mask {
        @Param({"true", "false"})
        public String mask;

        @Setup(Level.Trial)
        public void setup() { System.setProperty("causal.dsep.mask", mask); }
    }

    /** Rotating cursor over the pairs. */
    @State(Scope.Thread)
    public static class Cursor {
        int next;

        int next(Data d) {
            int i = next;
            next = next + 1 == d.t.length ? 0 : next + 1;
            return i;
        }
    }

    /** The legacy enumeration's finished pairs (the others are censored). */
    @State(Scope.Thread)
    public static class Legacy {
        static final long CAP_NANOS = TimeUnit.SECONDS.toNanos(QueryTimeout.SECONDS);
        LegacyEnumeration legacy;
        List<Workloads.Pair> finished = new ArrayList<>();
        int next;

        @Setup(Level.Trial)
        public void setup(Data d) {
            legacy = new LegacyEnumeration(d.g);
            Censor censor = new Censor("legacy", d.dataset);
            StringBuilder rows = new StringBuilder();
            List<Integer> done = new ArrayList<>();
            Map<Integer, String> k = new HashMap<>();
            for (int i = 0; i < d.pairs.size(); i++) {  // pass 1: which pairs finish under the cap
                Workloads.Pair p = d.pairs.get(i);
                String args = p.t() + ";" + p.y();
                boolean skipped = censor.censored("decide");
                List<Set<String>> sets = skipped ? null : run(p, System.nanoTime() + CAP_NANOS);
                if (sets == null) {
                    censor.record("decide", args, skipped ? 0 : QueryTimeout.TIMEOUT);
                    rows.append(String.join(",", d.dataset, p.t(), p.y(), "", "", "", "true")).append('\n');
                    continue;
                }
                finished.add(p);
                done.add(i);
                k.put(i, sets.isEmpty() ? "none" : String.valueOf(sets.get(0).size()));
            }
            // pass 2: warm both for ~1 s each; pass 3: per-pair medians
            for (long end = System.nanoTime() + 1_000_000_000L; !done.isEmpty() && System.nanoTime() < end; ) {
                for (int i : done) {
                    legacy.candidateAdjustmentSets(d.pairs.get(i).t(), d.pairs.get(i).y(), Long.MAX_VALUE);
                    if (System.nanoTime() > end) break;
                }
            }
            for (long end = System.nanoTime() + 1_000_000_000L; System.nanoTime() < end; ) {
                for (int i = 0; i < d.t.length; i++) Adjustment.decide(d.g, d.t[i], d.y[i]);
            }
            for (int i : done) {
                Workloads.Pair p = d.pairs.get(i);
                int ti = d.t[i], yi = d.y[i];
                double legacyUs = medianUs(() -> legacy.candidateAdjustmentSets(p.t(), p.y(), Long.MAX_VALUE));
                double decideUs = medianUs(() -> Adjustment.decide(d.g, ti, yi));
                rows.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%.4f,%.4f,false%n", d.dataset, p.t(), p.y(), k.get(i),
                        legacyUs, decideUs));
            }
            writePairs(rows.toString());
        }

        /** The legacy answer, or null if it hit the cap. */
        private List<Set<String>> run(Workloads.Pair p, long deadline) {
            try {
                return legacy.candidateAdjustmentSets(p.t(), p.y(), deadline);
            } catch (LegacyEnumeration.Timeout | OutOfMemoryError e) {  // the July code materializes every combination
                return null;
            }
        }

        /** Median µs over repetitions for ~100 ms (at least 3, at most 1000). */
        private static double medianUs(Runnable r) {
            List<Long> ns = new ArrayList<>();
            long budget = System.nanoTime() + 100_000_000L;
            for (int k = 0; k < 1000 && (k < 3 || System.nanoTime() < budget); k++) {
                long s = System.nanoTime();
                r.run();
                ns.add(System.nanoTime() - s);
            }
            Collections.sort(ns);
            return ns.get(ns.size() / 2) / 1e3;
        }

        private static void writePairs(String rows) {
            String timeouts = System.getProperty("bench.timeouts");
            if (timeouts == null || rows.isEmpty()) return;
            try {
                Path p = Path.of(timeouts).resolveSibling("legacy_pairs.csv");
                if (!Files.exists(p)) Files.writeString(p, "dataset,t,y,k,legacy_us,decide_us,censored\n");
                Files.writeString(p, rows, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Benchmark
    public boolean dsep(Data d, Triples s, Mask m) {
        int i = s.next();
        return DSeparation.separated(d.g, s.x[i], s.y[i], s.z[i]);
    }

    @Benchmark
    public boolean dsepReference(Data d, Triples s) {
        int i = s.next();
        return DSeparationReference.separated(d.g, s.xs.get(i), s.ys.get(i), s.zs.get(i), Set.of());
    }

    @Benchmark
    public Adjustment.Decision decide(Data d, Cursor c) {
        int i = c.next(d);
        return Adjustment.decide(d.g, d.t[i], d.y[i]);
    }

    @Benchmark
    public Optional<long[]> findMinimal(Data d, Cursor c) {
        int i = c.next(d);
        return Adjustment.findMinimal(d.g, d.t[i], d.y[i]);
    }

    @Benchmark
    public int listMinimal100(Data d, Cursor c) {
        int i = c.next(d);
        int[] count = {0};
        Adjustment.listMinimal(d.g, d.t[i], d.y[i], 100, s -> count[0]++);
        return count[0];
    }

    /** Finished pairs only; with none (every pair censored) it measures nothing and the cell reads t/o. */
    @Benchmark
    public List<Set<String>> decideReference(Data d, Legacy l) {
        if (l.finished.isEmpty()) return List.of();
        Workloads.Pair p = l.finished.get(l.next);
        l.next = l.next + 1 == l.finished.size() ? 0 : l.next + 1;
        return l.legacy.candidateAdjustmentSets(p.t(), p.y(), Long.MAX_VALUE);
    }
}
