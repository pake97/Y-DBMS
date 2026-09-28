package com.example.causal.runtime.index;

import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard against leaving a traversal in the indexed path: on a dense graph a
 * traversal pays for every edge, the closure lookup does not. Not a paper number:
 * no JMH, no fork control. Excluded from CI via the {@code slow} tag.
 */
@Tag("slow")
class IndexSmokeTimingTest {

    @Test
    void indexedAncestorsAreMuchFasterThanDfs() {
        // Dense on purpose: the DFS only walks the ancestor subgraph, so both paths
        // cost ~|answer| on sparse graphs (randomDag(5000, 0.002) gives ~1.6x).
        // The index is edge-independent; the DFS pays every edge inside an(v).
        CompiledCausalGraph g = Graphs.randomDag(1000, 0.3, 0);
        ReachabilityIndex idx = new ReachabilityIndex(g);

        // warm-up
        median(g, 2_000, idx::ancestors);
        median(g, 50, idx::ancestorsDfs);

        long index = median(g, 10_000, idx::ancestors);
        long dfs = median(g, 100, idx::ancestorsDfs);
        System.out.printf("ancestors median: index %d ns, dfs %d ns (%.1fx)%n", index, dfs, (double) dfs / index);
        assertTrue(dfs >= 10 * index, "index " + index + " ns vs dfs " + dfs + " ns");
    }

    private static long median(CompiledCausalGraph g, int calls, Function<String, ?> query) {
        Random rnd = new Random(1);
        long[] t = new long[calls];
        for (int k = 0; k < calls; k++) {
            String id = g.idOf(rnd.nextInt(g.size()));
            long start = System.nanoTime();
            query.apply(id);
            t[k] = System.nanoTime() - start;
        }
        Arrays.sort(t);
        return t[calls / 2];
    }
}
