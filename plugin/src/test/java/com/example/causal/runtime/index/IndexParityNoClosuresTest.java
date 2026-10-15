package com.example.causal.runtime.index;

import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The layer-pruned branch ({@code causal.closures.budget} below the closure size)
 * and the DFS baseline ({@code causal.index=off}) answer exactly what the DFS answers.
 */
class IndexParityNoClosuresTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("causal.closures.budget");
        System.clearProperty("causal.index");
    }

    private static CompiledCausalGraph withoutClosures(Supplier<CompiledCausalGraph> build) {
        System.setProperty("causal.closures.budget", "1");
        try {
            CompiledCausalGraph g = build.get();
            assertEquals(g.size() == 0, g.hasClosures(), "closures must be skipped");
            return g;
        } finally {
            System.clearProperty("causal.closures.budget");
        }
    }

    @ParameterizedTest(name = "pruned randomDag(n={0}, d={1}, seed={2})")
    @MethodSource("com.example.causal.runtime.index.IndexParityTest#randomFixtures")
    void prunedBranchMatchesDfs(int n, double density, long seed) {
        assertDfsParity(withoutClosures(() -> Graphs.randomDag(n, density, seed)));
    }

    @Test
    void prunedBranchMatchesDfsOnSeedDag() {
        assertDfsParity(withoutClosures(Graphs::fromSeedDag));
    }

    @Test
    void indexOffRoutesToDfs() {
        CompiledCausalGraph g = Graphs.randomDag(130, 0.05, 1);
        System.setProperty("causal.index", "off");
        ReachabilityIndex off = new ReachabilityIndex(g);
        for (int i = 0; i < g.size(); i++) {
            assertEquals(off.ancestorsDfs(g.idOf(i)), off.ancestors(g.idOf(i)));
        }
        assertTrue(off.visitedNodes() > 0, "the index=off branch must traverse");
        System.clearProperty("causal.index");
        ReachabilityIndex on = new ReachabilityIndex(g);
        on.ancestors(g.idOf(0));
        assertEquals(0, on.visitedNodes(), "the closure branch must not traverse");
    }

    @Test
    void layerPruningVisitsFewerNodesThanPlainDfs() {
        CompiledCausalGraph g = withoutClosures(() -> Graphs.randomDag(1000, 0.003, 0));
        ReachabilityIndex pruned = new ReachabilityIndex(g);
        ReachabilityIndex plain = new ReachabilityIndex(g);
        Random rnd = new Random(7);
        for (int k = 0; k < 1000; k++) {
            int u = rnd.nextInt(g.size()), v = rnd.nextInt(g.size());
            boolean expected = plain.descendantsDfs(g.idOf(u)).contains(g.idOf(v));
            assertEquals(expected, pruned.reachesPruned(u, v), "reaches " + u + "," + v);
        }
        assertTrue(pruned.visitedNodes() < plain.visitedNodes(),
                "pruned " + pruned.visitedNodes() + " vs plain " + plain.visitedNodes());
    }

    static void assertDfsParity(CompiledCausalGraph g) {
        ReachabilityIndex idx = new ReachabilityIndex(g);
        int n = g.size();
        for (int i = 0; i < n; i++) {
            String id = g.idOf(i);
            var de = idx.descendantsDfs(id);
            assertEquals(idx.ancestorsDfs(id), idx.ancestors(id), "ancestors of " + id);
            assertEquals(de, idx.descendants(id), "descendants of " + id);
            int step = n <= 130 ? 1 : 37;  // all pairs on small graphs, a stride on n = 1000
            for (int j = 0; j < n; j += step) {
                assertEquals(de.contains(g.idOf(j)), idx.hasDirectedPath(id, g.idOf(j)), "reaches " + i + "," + j);
            }
        }
    }
}
