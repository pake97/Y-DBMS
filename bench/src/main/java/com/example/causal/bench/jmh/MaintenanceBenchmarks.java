package com.example.causal.bench.jmh;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.graph.SnapshotFactory;
import com.example.causal.bench.maintenance.EditStreams;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.compile.PatchStats;
import com.example.causal.runtime.compile.SnapshotPatcher;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RQ3: {@code SnapshotPatcher.apply} against a full recompile ({@code GraphBuilder.build} of the
 * edited graph), over {@value #BATCHES} random batches of {@code batch} edits of one {@code type}
 * (see {@link EditStreams}), rotating. When the patch declines (deletion cones over
 * {@code causal.patch.threshold}, a fraction of |V|), {@code patch} pays the recompile too, as
 * the listener's caller would. The trial setup applies every batch once and appends its cone sizes
 * and whether it fell back to {@code rq3_edits.csv} next to {@code -Dbench.timeouts}.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class MaintenanceBenchmarks {

    static final int BATCHES = 10;
    static final long SEED = 20261111L;

    @Param({"asia", "alarm"})
    public String dataset;

    @Param({"insert", "delete", "active", "latent", "addvar", "remove"})
    public String type;

    @Param({"1"})
    public int batch;

    /** causal.patch.threshold (fraction of |V|); the sweep varies it on the delete stream. */
    @Param({"0.5"})
    public String threshold;

    private List<EditStreams.Item> items;
    private int next;

    @Setup(Level.Trial)
    public void setup() {
        System.setProperty("causal.patch.threshold", threshold);
        EdgeList el = EdgeListLoader.load(dataset);
        CompiledCausalGraph g0 = SnapshotFactory.fromEdgeList(el);
        items = EditStreams.generate(el, g0, type, batch, BATCHES, SEED);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            EditStreams.Item it = items.get(i);
            PatchStats before = SnapshotPatcher.stats();
            boolean fallback = SnapshotPatcher.apply(it.base(), it.batch()) == null;
            long thresholdFallbacks = SnapshotPatcher.stats().fallbacksThreshold() - before.fallbacksThreshold();
            rows.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%d,%d,%d,%d,%b,%d%n", dataset, type, batch, threshold, i,
                    it.base().size(), it.coneSum(), it.coneMax(), fallback, thresholdFallbacks));
        }
        append("rq3_edits.csv", "dataset,type,batch,threshold,index,n,cone_sum,cone_max,fallback,threshold_fallbacks\n",
                rows.toString());
    }

    private EditStreams.Item item() {
        EditStreams.Item it = items.get(next);
        next = next + 1 == items.size() ? 0 : next + 1;
        return it;
    }

    @Benchmark
    public CompiledCausalGraph patch() {
        EditStreams.Item it = item();
        CompiledCausalGraph g = SnapshotPatcher.apply(it.base(), it.batch());
        return g != null ? g : GraphBuilder.build(it.vars(), it.edges(), Map.of());
    }

    @Benchmark
    public CompiledCausalGraph recompile() {
        EditStreams.Item it = item();
        return GraphBuilder.build(it.vars(), it.edges(), Map.of());
    }

    /** Appends to a CSV next to -Dbench.timeouts (the run directory); nothing without it. */
    static void append(String name, String header, String rows) {
        String timeouts = System.getProperty("bench.timeouts");
        if (timeouts == null || rows.isEmpty()) return;
        try {
            Path p = Path.of(timeouts).resolveSibling(name);
            if (!Files.exists(p)) Files.writeString(p, header);
            Files.writeString(p, rows, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
