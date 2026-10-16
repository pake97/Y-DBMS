package com.example.causal.testsupport;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A patched snapshot against a full build of the same graph. Dense indices differ between
 * the two (a patch keeps old indices, appends new ones and leaves holes), so every
 * comparison goes through the variable ids.
 */
public final class SnapshotParity {

    private SnapshotParity() {}

    /**
     * Same variables, latent flags, adjacency, closures and layers; {@code topoPos} increases
     * along every edge of {@code patched}, and {@code topoOrder()} agrees with {@code topoIndex}.
     */
    public static void assertEquivalent(CompiledCausalGraph patched, CompiledCausalGraph rebuilt, String where) {
        int n = rebuilt.size();
        assertEquals(rebuilt.variables(), patched.variables(), where + ": variables");
        int[] perm = new int[n];
        for (int r = 0; r < n; r++) {
            perm[r] = patched.indexOf(rebuilt.idOf(r));
            assertTrue(perm[r] >= 0, where + ": missing " + rebuilt.idOf(r));
        }
        for (int r = 0; r < n; r++) {
            int p = perm[r];
            String id = rebuilt.idOf(r);
            assertEquals(rebuilt.variableAt(r).latent(), patched.variableAt(p).latent(), where + ": latent " + id);
            assertEquals(rebuilt.variableAt(r).observed(), patched.variableAt(p).observed(), where + ": observed " + id);
            assertEquals(rebuilt.childrenOf(id), patched.childrenOf(id), where + ": children " + id);
            assertEquals(rebuilt.parentsOf(id), patched.parentsOf(id), where + ": parents " + id);
            sameNeighbours(rebuilt.childrenOf(r), patched.childrenOf(p), perm, where + ": CSR children " + id);
            sameNeighbours(rebuilt.parentsOf(r), patched.parentsOf(p), perm, where + ": CSR parents " + id);
            sameRow(rebuilt.ancestorBits(r), patched.ancestorBits(p), perm, where + ": ancestors " + id);
            sameRow(rebuilt.descendantBits(r), patched.descendantBits(p), perm, where + ": descendants " + id);
            assertEquals(rebuilt.layer(r), patched.layer(p), where + ": layer " + id);
            for (int k = 0; k < patched.childCount(p); k++) {
                int c = patched.child(p, k);
                assertTrue(patched.topoPosition(p) < patched.topoPosition(c),
                        where + ": topoPos not monotone on " + id + " -> " + patched.idOf(c));
            }
            assertEquals(id, patched.topoOrder().get(patched.topoIndex(id)), where + ": topoOrder vs topoIndex " + id);
        }
        assertEquals(n, patched.topoOrder().size(), where + ": topoOrder size");
    }

    private static void sameRow(long[] rebuiltRow, long[] patchedRow, int[] perm, String what) {
        assertEquals(BitSet64.cardinality(rebuiltRow), BitSet64.cardinality(patchedRow), what);
        BitSet64.stream(rebuiltRow).forEach(j -> assertTrue(BitSet64.get(patchedRow, perm[j]), what));
    }

    private static void sameNeighbours(int[] rebuiltRow, int[] patchedRow, int[] perm, String what) {
        assertEquals(rebuiltRow.length, patchedRow.length, what);
        java.util.Set<Integer> got = new java.util.HashSet<>();
        for (int j : patchedRow) got.add(j);
        for (int j : rebuiltRow) assertTrue(got.contains(perm[j]), what);
    }
}
