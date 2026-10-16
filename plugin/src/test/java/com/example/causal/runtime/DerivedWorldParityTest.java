package com.example.causal.runtime;

import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;
import com.example.causal.testsupport.Graphs;
import com.example.causal.testsupport.SnapshotParity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Derived worlds against a full build: for 200 random intervened sets of size 1 to 3, the
 * world equals {@link GraphBuilder#build} of the base edges minus the in-edges of the set
 * (closures, layers, adjacency, latent/observed), and every closure row outside the
 * recomputed cones is the base's own array. Prints a WP4REPORT line per fixture.
 */
class DerivedWorldParityTest {

    private static final int WORLDS = 200;

    static Stream<Arguments> fixtures() {
        Map<String, Supplier<CompiledCausalGraph>> graphs = new LinkedHashMap<>();
        for (String ds : List.of("asia", "sachs", "alarm", "hepar2")) graphs.put(ds, () -> Graphs.fromBnlearn(ds));
        graphs.put("seed DAG", Graphs::fromSeedDagWithLatents);
        graphs.put("policy DAG", Graphs::fromPolicyDag);
        graphs.put("randomDag(1000, 0.004)", () -> Graphs.randomDag(1000, 0.004, 0));
        return graphs.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void derivedWorldEqualsRebuildAndSharesUntouchedRows(String name, Supplier<CompiledCausalGraph> fixture) {
        CompiledCausalGraph g = fixture.get();
        DefaultCausalGraphRuntime base = new DefaultCausalGraphRuntime();
        base.install(g);
        WorldRegistry worlds = new WorldRegistry(() -> base);
        Random rnd = new Random(name.hashCode());
        List<Double> shared = new ArrayList<>();
        List<Long> micros = new ArrayList<>();
        int fallbacks = 0, nothingCut = 0;

        for (int k = 0; k < WORLDS; k++) {
            Set<String> xs = new TreeSet<>();
            int size = 1 + rnd.nextInt(3);
            while (xs.size() < Math.min(size, g.liveCount())) xs.add(g.idOf(rnd.nextInt(g.size())));
            Set<VariableRef> refs = new LinkedHashSet<>();
            xs.forEach(x -> refs.add(new VariableRef(x)));
            worlds.define("w", WorldRegistry.BASE, refs);
            CompiledCausalGraph w = worlds.snapshot("w");
            WorldRegistry.Derivation d = worlds.derivation("w");
            String where = name + " do" + xs;

            SnapshotParity.assertEquivalent(w, rebuild(g, xs), where);
            int cut = 0;
            for (String x : xs) cut += g.parentsOf(x).size();
            assertEquals(cut, d.removedEdges(), where);
            assertEquals(2 * g.liveCount(), d.totalRows(), where);

            if (d.fallback()) {
                fallbacks++;
            } else {
                long[] ancCone = new long[g.wordCount()], desCone = new long[g.wordCount()];
                for (String x : xs) {
                    int xi = g.indexOf(x);
                    if (g.parentCount(xi) == 0) continue;
                    BitSet64.or(ancCone, g.descendantBits(xi));
                    BitSet64.set(ancCone, xi);
                    for (int p = 0; p < g.parentCount(xi); p++) {
                        int u = g.parent(xi, p);
                        BitSet64.or(desCone, g.ancestorBits(u));
                        BitSet64.set(desCone, u);
                    }
                }
                for (int i = 0; i < g.size(); i++) {
                    if (BitSet64.get(ancCone, i)) assertNotSame(g.ancestorBits(i), w.ancestorBits(i), where);
                    else assertSame(g.ancestorBits(i), w.ancestorBits(i), where + " anc row " + g.idOf(i));
                    if (BitSet64.get(desCone, i)) assertNotSame(g.descendantBits(i), w.descendantBits(i), where);
                    else assertSame(g.descendantBits(i), w.descendantBits(i), where + " des row " + g.idOf(i));
                }
                assertEquals(BitSet64.cardinality(ancCone) + BitSet64.cardinality(desCone), d.coneSize(), where);
                if (d.removedEdges() == 0) nothingCut++;   // intervened sources: the base itself
                else shared.add((double) d.sharedRows() / d.totalRows());
            }
            micros.add(d.nanos() / 1000);
        }
        assertSame(g, base.currentSnapshot(), "the base is never touched");
        Collections.sort(shared);
        Collections.sort(micros);
        System.out.printf(Locale.ROOT, "WP4REPORT %s | n %d | worlds %d | nothing cut %d | fallbacks %d | patched with cuts %d | "
                        + "sharedRows/totalRows median %.3f max %.3f | intervene us median %d max %d%n",
                name, g.liveCount(), WORLDS, nothingCut, fallbacks, shared.size(),
                shared.isEmpty() ? 0 : shared.get(shared.size() / 2), shared.isEmpty() ? 0 : shared.get(shared.size() - 1),
                micros.get(micros.size() / 2), micros.get(micros.size() - 1));
    }

    /** The base's variables and edges minus the in-edges of {@code xs}, built from scratch. */
    private static CompiledCausalGraph rebuild(CompiledCausalGraph g, Set<String> xs) {
        List<Variable> vars = new ArrayList<>();
        List<String[]> edges = new ArrayList<>();
        for (int i = 0; i < g.size(); i++) {
            vars.add(g.variableAt(i));
            for (String c : g.childrenOf(g.idOf(i))) if (!xs.contains(c)) edges.add(new String[]{g.idOf(i), c});
        }
        return GraphBuilder.build(vars, edges, Map.of());
    }
}
