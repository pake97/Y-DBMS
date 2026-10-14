package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.testsupport.Graphs;
import com.example.causal.testsupport.ReferenceFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal adjustment sets: every emitted set is valid and inclusion-minimal, families have no
 * duplicates, findMinimal and decide agree with the family, the delay between two outputs is
 * at most |R| + 1 findMinSep calls, and the families equal dagitty's (WP5a reference).
 */
class MinimalSeparatorTest {

    /** Longest single listMinimal call seen in this class (reported in the PR). */
    private static long longestNanos;
    private static String longestCall = "";

    @AfterAll
    static void report() {
        System.out.printf("WP2REPORT longest listMinimal %.1f ms (%s)%n", longestNanos / 1e6, longestCall);
    }

    // ── random fixtures ────────────────────────────────────────────────────

    @ParameterizedTest(name = "randomDag(n={0}, d={1}, seed={2})")
    @MethodSource("com.example.causal.runtime.index.IndexParityTest#randomFixtures")
    void randomDags(int n, double density, long seed) {
        CompiledCausalGraph g = Graphs.randomDag(n, density, seed);
        // 200 pairs with reaches(t, y); 20 on n = 1000, where families of the dense graphs are huge
        int pairs = n >= 1000 ? 20 : 200, limit = n >= 1000 ? 20 : 100;
        Random rnd = new Random(seed);
        int found = 0;
        for (int k = 0; k < 50 * pairs && found < pairs; k++) {
            int t = rnd.nextInt(n), y = rnd.nextInt(n);
            if (t == y || !g.reaches(t, y)) continue;
            found++;
            checkPair(g, t, y, limit, "randomDag(" + n + "," + density + "," + seed + ")");
        }
    }

    @Test
    void seedDag() {
        CompiledCausalGraph g = Graphs.fromSeedDagWithLatents();
        for (int t = 0; t < g.size(); t++)
            for (int y = 0; y < g.size(); y++)
                if (t != y && g.reaches(t, y)) checkPair(g, t, y, 0, "seed");
    }

    /** Validity, minimality, no duplicates, findMinimal/decide consistency, delay bound. */
    static List<Set<String>> checkPair(CompiledCausalGraph g, int t, int y, int limit, String where) {
        String label = where + " " + g.idOf(t) + "->" + g.idOf(y);
        int r = BitSet64.cardinality(new PathAnalyzer(g).eligibleBits(t, y));
        List<long[]> family = new ArrayList<>();
        int[] callsSinceEmit = {0}, maxCalls = {0};
        long start = System.nanoTime();
        Adjustment.listMinimal(g, t, y, limit, z -> {
            maxCalls[0] = Math.max(maxCalls[0], callsSinceEmit[0]);
            callsSinceEmit[0] = 0;
            family.add(z);
        }, () -> callsSinceEmit[0]++);
        long elapsed = System.nanoTime() - start;
        synchronized (MinimalSeparatorTest.class) {
            if (elapsed > longestNanos) {
                longestNanos = elapsed;
                longestCall = label + ", " + family.size() + " sets";
            }
        }
        assertTrue(maxCalls[0] <= r + 1, label + ": " + maxCalls[0] + " findMinSep calls between emits, |R| = " + r);

        Set<Set<String>> distinct = new HashSet<>();
        for (long[] z : family) {
            assertTrue(Adjustment.isValid(g, t, y, z), label + ": invalid " + g.toIds(z));
            assertTrue(removalMinimal(g, t, y, z), label + ": not minimal " + g.toIds(z));
            assertTrue(distinct.add(g.toIds(z)), label + ": duplicate " + g.toIds(z));
        }
        boolean complete = limit <= 0 || family.size() < limit;
        Optional<long[]> one = Adjustment.findMinimal(g, t, y);
        assertEquals(family.isEmpty(), one.isEmpty(), label + ": findMinimal vs family");
        if (complete && one.isPresent()) {
            assertTrue(distinct.contains(g.toIds(one.get())), label + ": findMinimal not in the family");
        }
        assertEquals(!family.isEmpty(), Adjustment.decide(g, t, y).identifiable(), label + ": decide vs family");
        List<Set<String>> out = new ArrayList<>();
        family.forEach(z -> out.add(g.toIds(z)));
        return out;
    }

    /** No one-element removal is valid (enough: all sets lie in An(t ∪ y), where validity is monotone). */
    static boolean removalMinimal(CompiledCausalGraph g, int t, int y, long[] z) {
        for (int v : BitSet64.stream(z).toArray()) {
            long[] smaller = BitSet64.copy(z);
            PathAnalyzer.clear(smaller, v);
            if (Adjustment.isValid(g, t, y, smaller)) return false;
        }
        return true;
    }

    // ── WP5a reference: dagitty families (addendum B) ──────────────────────

    @Test
    void familiesMatchTheDagittyReference() {
        for (String ds : List.of("asia", "sachs", "alarm", "hepar2", "covid")) {
            CompiledCausalGraph g = ds.equals("covid") ? Graphs.fromSeedDagWithLatents() : Graphs.fromBnlearn(ds);
            int pairs = 0, canonicalAndIdentifiable = 0, compared = 0, equal = 0, nullFamilies = 0, nullValid = 0;
            List<String> mismatches = new ArrayList<>();
            for (JsonNode p : ReferenceFiles.adjustment(ds)) {
                pairs++;
                int t = g.indexOf(p.get("t").asText()), y = g.indexOf(p.get("y").asText());
                String label = ds + " " + p.get("t").asText() + "->" + p.get("y").asText();
                Adjustment.Decision d = Adjustment.decide(g, t, y);
                Set<String> canonical = d.identifiable() ? g.toIds(d.canonical()) : null;
                Set<String> refCanonical = p.get("canonical").isNull() ? null : ReferenceFiles.ids(p.get("canonical"));
                if (Objects.equals(canonical, refCanonical) && d.identifiable() == p.get("identifiable").asBoolean()) {
                    canonicalAndIdentifiable++;
                } else {
                    mismatches.add(label + " canonical ours=" + canonical + " ref=" + refCanonical);
                }
                boolean full = !p.get("minimal").isNull();
                List<Set<String>> family = checkPair(g, t, y, full ? 0 : 100, ds);
                if (full) {
                    compared++;
                    Set<Set<String>> ref = new HashSet<>();
                    p.get("minimal").forEach(s -> ref.add(ReferenceFiles.ids(s)));
                    if (new HashSet<>(family).equals(ref)) equal++;
                    else mismatches.add(label + " family ours=" + family + " ref=" + ref);
                } else {
                    nullFamilies++;
                    nullValid++;   // checkPair asserted validity and minimality of every emitted set
                }
            }
            System.out.printf("WP2REPORT adjustment %s pairs %d canonical+identifiable %d families compared %d equal %d null %d all-valid-minimal %d%n",
                    ds, pairs, canonicalAndIdentifiable, compared, equal, nullFamilies, nullValid);
            mismatches.forEach(m -> System.out.println("WP2REPORT mismatch " + m));
            assertEquals(List.of(), mismatches, ds);
        }
    }

    // ── smoke timing (tag slow) ────────────────────────────────────────────

    @Test
    @Tag("slow")
    void decideOnALargeSparseGraphIsFast() {
        CompiledCausalGraph g = Graphs.randomDag(5000, 0.002, 0);
        Random rnd = new Random(1);
        long start = System.nanoTime();
        for (int k = 0; k < 1000; k++) {
            int t = rnd.nextInt(g.size()), y = rnd.nextInt(g.size());
            Adjustment.decide(g, t, y);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("WP2REPORT decide 1000 pairs on randomDag(5000, 0.002): %.2f s%n", seconds);
        assertTrue(seconds < 5, "decide took " + seconds + " s");
    }
}
