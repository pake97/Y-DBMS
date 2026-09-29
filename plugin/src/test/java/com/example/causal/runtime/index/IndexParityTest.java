package com.example.causal.runtime.index;

import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.testsupport.Graphs;
import com.example.causal.testsupport.SyntheticDataSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The precomputed closures must answer exactly what the DFS answers. The
 * word-boundary sizes (63/64/65/130) are the ones that catch off-by-ones.
 */
class IndexParityTest {

    static Stream<Arguments> randomFixtures() {
        List<Arguments> out = new ArrayList<>();
        for (int n : new int[]{1, 2, 63, 64, 65, 130, 1000})
            for (double d : new double[]{0.0, 0.05, 0.3})
                for (long seed = 0; seed <= 2; seed++)
                    out.add(Arguments.of(n, d, seed));
        return out.stream();
    }

    @ParameterizedTest(name = "randomDag(n={0}, d={1}, seed={2})")
    @MethodSource("randomFixtures")
    void randomDags(int n, double density, long seed) {
        assertParity(Graphs.randomDag(n, density, seed));
    }

    @Test
    void seedDag() {
        CompiledCausalGraph g = Graphs.fromSeedDag();
        assertEquals(25, g.size());
        assertParity(g);
    }

    /**
     * Fresh DB per config: wiping and reloading one DB trips the change
     * listener on DETACH DELETE (it reads ids of already-deleted endpoints).
     */
    @Tag("synthetic")
    @ParameterizedTest(name = "synthetic config {0}")
    @ValueSource(ints = {1, 2, 3})
    void syntheticConfig(int configId) {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            SyntheticDataSupport.load(db, configId, "1K");
            try (Transaction tx = db.beginTx()) {
                Result r = tx.execute("CALL causal.compile()");
                while (r.hasNext()) r.next();
                tx.commit();
            }
            CompiledCausalGraph g = (CompiledCausalGraph) RuntimeRegistry.getInstance().currentSnapshot();
            assertTrue(g.size() > 0);
            assertParity(g);
        }
    }

    static void assertParity(CompiledCausalGraph g) {
        int n = g.size();
        ReachabilityIndex idx = new ReachabilityIndex(g);
        List<Set<String>> dfsDesc = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            String id = g.idOf(i);
            assertEquals(i, g.indexOf(id));

            // 1. index answers == DFS answers
            Set<String> desc = idx.descendantsDfs(id);
            assertEquals(idx.ancestorsDfs(id), idx.ancestors(id), "ancestors of " + id);
            assertEquals(desc, idx.descendants(id), "descendants of " + id);
            dfsDesc.add(desc);

            // 5. no self bits
            assertFalse(BitSet64.get(g.ancestorBits(i), i), "self in an(" + id + ")");
            assertFalse(BitSet64.get(g.descendantBits(i), i), "self in de(" + id + ")");

            // 6. O(1) topoIndex agrees with the list
            assertEquals(g.topoOrder().indexOf(id), g.topoIndex(id));

            // 7. CSR slices match the maps, in order
            assertArrayEquals(indices(g, g.childrenOf(id)), g.childrenOf(i), "children of " + id);
            assertArrayEquals(indices(g, g.parentsOf(id)), g.parentsOf(i), "parents of " + id);

            // 4. every edge goes up in layer and topological position
            for (int c : g.childrenOf(i)) {
                assertTrue(g.layer(c) > g.layer(i), "layer " + id + "->" + g.idOf(c));
                assertTrue(g.topoPosition(c) > g.topoPosition(i), "topo " + id + "->" + g.idOf(c));
            }
        }

        // 2. duality and 3. reaches(): all pairs (DFS answers are cached per source)
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                assertEquals(BitSet64.get(g.ancestorBits(u), v), BitSet64.get(g.descendantBits(v), u),
                        "duality " + u + "," + v);
                assertEquals(dfsDesc.get(u).contains(g.idOf(v)), g.reaches(u, v), "reaches " + u + "," + v);
            }
        }

        assertEquals(-1, g.indexOf("__missing__"));
        assertEquals(-1, g.topoIndex("__missing__"));
        assertTrue(idx.ancestors("__missing__").isEmpty());
        assertFalse(idx.hasDirectedPath("__missing__", n > 0 ? g.idOf(0) : "x"));
    }

    private static int[] indices(CompiledCausalGraph g, Set<String> ids) {
        return ids.stream().mapToInt(g::indexOf).toArray();
    }
}
