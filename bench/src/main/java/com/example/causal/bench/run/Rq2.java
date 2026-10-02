package com.example.causal.bench.run;

import com.example.causal.bench.data.Datasets;
import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.data.Workloads;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * RQ2: per dataset and branch (closures / layer-pruned), build time, retained heap
 * of the snapshot, analytic closure size, and median ancestors / reaches latency.
 * Build time here is a plain median over repeated builds; the JMH CompileBenchmarks
 * numbers are the ones the paper reports, this CSV is for the crossover analysis.
 *
 * <p>usage: Rq2 <out.csv> <dataset,dataset,...>
 */
public final class Rq2 {

    private static final MemoryMXBean MEM = ManagementFactory.getMemoryMXBean();
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("dataset,n,m,branch,build_ms_median,heap_bytes,closure_bytes,ancestors_us_median,reaches_us_median");
            for (String id : args[1].split(",")) {
                EdgeList el = EdgeListLoader.load(id);
                List<Workloads.Pair> pairs = Workloads.pairs(id);
                for (String branch : new String[]{"closures", "pruned"}) {
                    if (branch.equals("pruned")) System.setProperty("causal.closures.budget", "0");
                    else System.clearProperty("causal.closures.budget");
                    double build = buildMedianMs(el);
                    long before = usedAfterGc();
                    CompiledCausalGraph g = SnapshotFactory.fromEdgeList(el);
                    long heap = usedAfterGc() - before;
                    ReachabilityIndex idx = new ReachabilityIndex(g);
                    double anc = medianUs(pairs, p -> sink = idx.ancestors(p.y()));
                    double reach = medianUs(pairs, p -> sink = idx.hasDirectedPath(p.t(), p.y()));
                    w.printf("%s,%d,%d,%s,%.3f,%d,%d,%.3f,%.3f%n", id, el.n(), el.m(), branch, build, heap,
                            CompiledCausalGraph.closureBytes(el.n()), anc, reach);
                    w.flush();
                    sink = g;
                    System.out.printf("rq2 %s %s build %.2f ms heap %d B%n", id, branch, build, heap);
                }
            }
        } finally {
            System.clearProperty("causal.closures.budget");
        }
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(50); }
        return MEM.getHeapMemoryUsage().getUsed();
    }

    /** Median of at least 5 builds and at least 1 s of building, after 3 warm-up builds. */
    private static double buildMedianMs(EdgeList el) {
        for (int i = 0; i < 3; i++) sink = SnapshotFactory.fromEdgeList(el);
        double[] t = new double[64];
        int k = 0;
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (k < t.length && (k < 5 || System.nanoTime() < deadline)) {
            long s = System.nanoTime();
            sink = SnapshotFactory.fromEdgeList(el);
            t[k++] = (System.nanoTime() - s) / 1e6;
        }
        return median(Arrays.copyOf(t, k));
    }

    /** Median per-query latency over 5 passes of the workload after one warm-up pass. */
    private static double medianUs(List<Workloads.Pair> pairs, java.util.function.Consumer<Workloads.Pair> q) {
        pairs.forEach(q);
        double[] t = new double[pairs.size() * 5];
        int k = 0;
        for (int pass = 0; pass < 5; pass++) {
            for (Workloads.Pair p : pairs) {
                long s = System.nanoTime();
                q.accept(p);
                t[k++] = (System.nanoTime() - s) / 1e3;
            }
        }
        return median(t);
    }

    private static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }
}
