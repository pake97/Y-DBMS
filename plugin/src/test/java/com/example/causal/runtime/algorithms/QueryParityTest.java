package com.example.causal.runtime.algorithms;

import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.testsupport.Graphs;
import com.example.causal.testsupport.ReferenceFiles;
import com.example.causal.testsupport.SyntheticDataSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP2 parity: the bitset operators against the reference implementations they replace, and
 * d-separation against the pgmpy/dagitty verdicts of WP5a ({@code bench/reference}).
 */
class QueryParityTest {

    private static final int[] Z_SIZES = {0, 1, 3, 10};

    @AfterEach
    void clearMask() {
        System.clearProperty("causal.dsep.mask");
    }

    // ── random fixtures of IndexParityTest, the seed DAG, the synthetic configs ──

    @ParameterizedTest(name = "randomDag(n={0}, d={1}, seed={2})")
    @MethodSource("com.example.causal.runtime.index.IndexParityTest#randomFixtures")
    void randomDags(int n, double density, long seed) {
        assertParity(Graphs.randomDag(n, density, seed), seed);
    }

    @Test
    void seedDag() {
        assertParity(Graphs.fromSeedDagWithLatents(), 7);
    }

    @Tag("synthetic")
    @ParameterizedTest(name = "synthetic config {0}")
    @ValueSource(ints = {1, 2, 3})
    void syntheticConfig(int configId) {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build()) {
            SyntheticDataSupport.load(neo4j.defaultDatabaseService(), configId, "1K");
            try (Transaction tx = neo4j.defaultDatabaseService().beginTx()) {
                Result r = tx.execute("CALL causal.compile()");
                while (r.hasNext()) r.next();
                tx.commit();
            }
            assertParity((CompiledCausalGraph) RuntimeRegistry.getInstance().currentSnapshot(), configId);
        } finally {
            RuntimeRegistry.setInstance(null);
        }
    }

    static void assertParity(CompiledCausalGraph g, long seed) {
        int n = g.size();
        if (n < 2) return;
        Random rnd = new Random(seed);
        PathAnalyzer pa = new PathAnalyzer(g);
        PathAnalyzerReference ref = new PathAnalyzerReference(g);

        // 1. the four path-role operators, 200 random ordered pairs
        for (int k = 0; k < 200; k++) {
            String a = g.idOf(rnd.nextInt(n)), b = g.idOf(rnd.nextInt(n));
            if (a.equals(b)) continue;
            assertEquals(ref.mediators(a, b), pa.mediators(a, b), "mediators " + a + "," + b);
            assertEquals(ref.colliders(a, b), pa.colliders(a, b), "colliders " + a + "," + b);
            assertEquals(ref.confounders(a, b), pa.confounders(a, b), "confounders " + a + "," + b);
            assertEquals(ref.backdoorVariables(a, b), pa.backdoorVariables(a, b), "backdoor " + a + "," + b);
        }

        // 2. d-separation: 2,000 random (X, Y, Z), plus Z containing a descendant of a collider
        for (int k = 0; k < 2000; k++) {
            List<Integer> perm = shuffled(n, rnd);
            int nx = 1 + rnd.nextInt(Math.min(2, n - 1)), ny = 1 + rnd.nextInt(Math.min(2, n - nx));
            int nz = Math.min(Z_SIZES[rnd.nextInt(Z_SIZES.length)], n - nx - ny);
            checkTriple(g, perm.subList(0, nx), perm.subList(nx, nx + ny), perm.subList(nx + ny, nx + ny + nz));
        }
        int colliderTriples = 0;
        for (int c = 0; c < n && colliderTriples < 200; c++) {
            if (g.parentCount(c) < 2 || g.childCount(c) == 0) continue;
            int x = g.parent(c, 0), y = g.parent(c, 1);
            int d = BitSet64.stream(g.descendantBits(c)).findFirst().orElseThrow();
            if (d == x || d == y) continue;
            checkTriple(g, List.of(x), List.of(y), List.of(d));
            colliderTriples++;
        }
    }

    /** New == reference, masked == unmasked, and the cut variant of the backdoor test == reference. */
    static boolean checkTriple(CompiledCausalGraph g, List<Integer> xs, List<Integer> ys, List<Integer> zs) {
        long[] x = bits(g, xs), y = bits(g, ys), z = bits(g, zs);
        Set<String> xi = ids(g, xs), yi = ids(g, ys), zi = ids(g, zs);
        boolean expected = DSeparationReference.separated(g, xi, yi, zi, Set.of());
        String label = xi + " ⊥ " + yi + " | " + zi;
        assertEquals(expected, DSeparation.separated(g, x, y, z), "masked " + label);
        System.setProperty("causal.dsep.mask", "false");
        assertEquals(expected, DSeparation.separated(g, x, y, z), "unmasked " + label);
        System.clearProperty("causal.dsep.mask");
        if (xs.size() == 1) {
            boolean cutRef = DSeparationReference.separated(g, xi, yi, zi, xi);
            assertEquals(cutRef, DSeparation.separated(g, x, y, z, x, null), "cut " + label);
        }
        return expected;
    }

    // ── WP5a reference verdicts (pgmpy == dagitty); addendum B and C ───────

    @Test
    void dSeparationMatchesReferenceVerdicts() {
        Map<String, int[]> counts = new LinkedHashMap<>();   // dataset -> {sepAgree, sepTotal, conAgree, conTotal}
        for (String ds : List.of("asia", "sachs", "alarm", "hepar2", "covid")) {
            CompiledCausalGraph g = ds.equals("covid") ? Graphs.fromSeedDagWithLatents() : Graphs.fromBnlearn(ds);
            int[] c = new int[4];
            for (JsonNode t : ReferenceFiles.dsep(ds)) {
                boolean verdict = t.get("pgmpy").asBoolean();
                List<Integer> xs = List.of(g.indexOf(t.get("x").asText()));
                List<Integer> ys = List.of(g.indexOf(t.get("y").asText()));
                List<Integer> zs = new ArrayList<>();
                t.get("z").forEach(v -> zs.add(g.indexOf(v.asText())));
                boolean ours = checkTriple(g, xs, ys, zs);   // also checks the reference implementation
                assertEquals(verdict, ours, ds + " " + t);
                c[verdict ? 1 : 3]++;
                if (ours == verdict) c[verdict ? 0 : 2]++;
            }
            counts.put(ds, c);
        }
        counts.forEach((ds, c) -> System.out.printf("WP2REPORT dsep %s separated %d/%d connected %d/%d%n",
                ds, c[0], c[1], c[2], c[3]));
        int connectedBnlearn = counts.entrySet().stream().filter(e -> !e.getKey().equals("covid"))
                .mapToInt(e -> e.getValue()[3]).sum();
        assertTrue(connectedBnlearn >= 100, "connected bnlearn triples in the parity set: " + connectedBnlearn);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static long[] bits(CompiledCausalGraph g, List<Integer> idx) {
        long[] b = new long[g.wordCount()];
        idx.forEach(i -> BitSet64.set(b, i));
        return b;
    }

    static Set<String> ids(CompiledCausalGraph g, List<Integer> idx) {
        Set<String> s = new LinkedHashSet<>();
        idx.forEach(i -> s.add(g.idOf(i)));
        return s;
    }

    static List<Integer> shuffled(int n, Random rnd) {
        List<Integer> p = new ArrayList<>(n);
        for (int i = 0; i < n; i++) p.add(i);
        Collections.shuffle(p, rnd);
        return p;
    }
}
