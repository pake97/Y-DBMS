package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;

/**
 * Bayes-Ball d-separation on the compiled DAG over dense indices and bitsets (cost tier (b)
 * of the paper, {@code O(|V|+|E|)}).
 *
 * <p>A ball state is a node with the direction it was entered from (from a parent, from a
 * child). Rules: at a node in {@code z}, a ball from a parent passes to the parents and a ball
 * from a child stops; at a node not in {@code z}, a ball from a parent passes to the children
 * and a ball from a child passes to the parents and the children. Every node of {@code x}
 * starts with both directions; {@code x} and {@code y} are connected as soon as a state's
 * node is in {@code y}.
 *
 * <p><b>Ancestral masking.</b> d-separation of {@code x} and {@code y} given {@code z} depends
 * only on the ancestral subgraph of {@code x ∪ y ∪ z} (Geiger, Verma and Pearl 1990), so a
 * node outside {@code ancestralMask(x, y, z)} is never enqueued. The mask stays ancestral
 * after cutting edges, so it is also exact in the proper backdoor graph. Disable it with
 * {@code -Dcausal.dsep.mask=false} (the RQ1 ablation).
 *
 * <p>Two visited bitsets and an {@code int[]} ring buffer of capacity {@code 2n} holding
 * {@code node*2 + dir}: no allocation per state.
 */
public final class DSeparation {

    private static final int FROM_CHILD = 0, FROM_PARENT = 1;

    private DSeparation() {}

    /** Without edge cuts. */
    public static boolean separated(CompiledCausalGraph g, long[] x, long[] y, long[] z) {
        return separated(g, x, y, z, null, null);
    }

    /**
     * @param cutOutgoing nodes whose outgoing edges are ignored (nullable: no cuts)
     * @param cutOnlyTo   if non-null, an outgoing edge {@code u→c} of a cut node is ignored only
     *                    when {@code c} is in {@code cutOnlyTo} (the proper backdoor graph cuts
     *                    {@code t→c} for the causal nodes {@code c} only)
     * @return true if {@code x} and {@code y} are d-separated given {@code z}; vacuously true for
     *         an empty {@code x} or {@code y}, false if they intersect
     */
    public static boolean separated(CompiledCausalGraph g, long[] x, long[] y, long[] z,
                                    long[] cutOutgoing, long[] cutOnlyTo) {
        if (BitSet64.isEmpty(x) || BitSet64.isEmpty(y)) return true;
        if (BitSet64.intersects(x, y)) return false;
        return firstReached(g, x, y, z, cutOutgoing, cutOnlyTo) < 0;
    }

    /**
     * The first node of {@code y} the ball reaches, or -1 if {@code x} and {@code y} are
     * d-separated given {@code z}. Assumes {@code x}, {@code y} non-empty and disjoint.
     */
    public static int firstReached(CompiledCausalGraph g, long[] x, long[] y, long[] z,
                                   long[] cutOutgoing, long[] cutOnlyTo) {
        int n = g.size();
        long[] mask = maskEnabled() ? ancestralMask(g, x, y, z) : null;
        long[] fromParent = new long[g.wordCount()];
        long[] fromChild = new long[g.wordCount()];
        int[] queue = new int[2 * n];
        int head = 0, size = 0;

        for (int v = 0; v < n; v++) {
            if (!BitSet64.get(x, v)) continue;
            BitSet64.set(fromParent, v);
            BitSet64.set(fromChild, v);
            queue[(head + size++) % queue.length] = v * 2 + FROM_PARENT;
            queue[(head + size++) % queue.length] = v * 2 + FROM_CHILD;
        }

        while (size > 0) {
            int state = queue[head];
            head = (head + 1) % queue.length;
            size--;
            int u = state >>> 1;
            boolean enteredFromParent = (state & 1) == FROM_PARENT;
            if (BitSet64.get(y, u)) return u;

            boolean inZ = BitSet64.get(z, u);
            boolean passToParents = inZ ? enteredFromParent : !enteredFromParent;
            boolean passToChildren = !inZ;
            if (passToParents) {
                for (int k = 0, m = g.parentCount(u); k < m; k++) {
                    int p = g.parent(u, k);
                    if ((mask == null || BitSet64.get(mask, p)) && !BitSet64.get(fromChild, p)) {
                        BitSet64.set(fromChild, p);
                        queue[(head + size++) % queue.length] = p * 2 + FROM_CHILD;
                    }
                }
            }
            if (passToChildren) {
                boolean cut = cutOutgoing != null && BitSet64.get(cutOutgoing, u);
                for (int k = 0, m = g.childCount(u); k < m; k++) {
                    int c = g.child(u, k);
                    if (cut && (cutOnlyTo == null || BitSet64.get(cutOnlyTo, c))) continue;
                    if ((mask == null || BitSet64.get(mask, c)) && !BitSet64.get(fromParent, c)) {
                        BitSet64.set(fromParent, c);
                        queue[(head + size++) % queue.length] = c * 2 + FROM_PARENT;
                    }
                }
            }
        }
        return -1;
    }

    /** {@code x | y | z | OR anc[v] for v in x|y|z}. */
    public static long[] ancestralMask(CompiledCausalGraph g, long[] x, long[] y, long[] z) {
        long[] seeds = BitSet64.copy(x);
        BitSet64.or(seeds, y);
        BitSet64.or(seeds, z);
        long[] mask = BitSet64.copy(seeds);
        BitSet64.stream(seeds).forEach(v -> BitSet64.or(mask, g.ancestorBits(v)));
        return mask;
    }

    /** {@code causal.dsep.mask} (default true), read per call so benchmarks can switch it. */
    static boolean maskEnabled() {
        return !"false".equals(System.getProperty("causal.dsep.mask"));
    }

    /** A bitset over {@code g} with the given dense indices set. */
    public static long[] bits(CompiledCausalGraph g, int... indices) {
        long[] b = new long[g.wordCount()];
        for (int i : indices) BitSet64.set(b, i);
        return b;
    }
}
