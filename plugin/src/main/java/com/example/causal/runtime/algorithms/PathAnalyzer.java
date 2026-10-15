package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;

import java.util.Set;

/**
 * Path-based structural queries over the closures of a {@link CompiledCausalGraph}
 * (cost tier (a) of the paper): every operator is a few word-wise operations on
 * {@code long[]} rows, plus one pass over the edges for colliders and one skeleton
 * BFS for backdoor variables.
 *
 * <p>For treatment {@code t} and outcome {@code y} (dense indices), with
 * {@code anc}/{@code des} the closures:
 * <pre>
 * mediators(t,y)   = des[t] &amp; anc[y]
 * cn(t,y)          = mediators(t,y) | {y}               if reaches(t,y), else empty   (causal nodes)
 * forb(t,y)        = {t} | cn(t,y) | OR_{c in cn} des[c]                              (forbidden set)
 * R(t,y)           = obs &amp; ~forb(t,y) &amp; ~{t,y}           obs = non-latent variables
 * adj(t,y)         = (anc[t] | anc[y]) &amp; R(t,y)                                        (canonical set)
 * confounders(x,y) = anc[x] &amp; anc[y]
 * colliders(x,y)   = { z not in anc[x]|anc[y]|{x,y} : a parent of z in des[x]|{x} and one in des[y]|{y} }
 * </pre>
 * Latent variables may block or open paths but are never eligible for adjustment
 * (Perković et al. 2018; dagitty's {@code adjustmentSets}).
 *
 * <p>Returned arrays are fresh; arrays read from the snapshot are never mutated.
 * The {@code Set<String>} methods are the id-level API for {@code DefaultCausalGraphRuntime}.
 */
public final class PathAnalyzer {

    private final CompiledCausalGraph graph;
    private final int words;
    /** Non-latent variables: the only ones eligible for adjustment sets. */
    private final long[] observed;

    public PathAnalyzer(CompiledCausalGraph graph) {
        this.graph = graph;
        this.words = graph.wordCount();
        this.observed = new long[words];
        for (int i = 0; i < graph.size(); i++) {
            if (!graph.variableAt(i).latent()) BitSet64.set(observed, i);
        }
    }

    // ── id-level API ────────────────────────────────────────────────────────

    /** Variables on a directed path {@code from → … → to}, endpoints excluded. */
    public Set<String> mediators(String from, String to) {
        int t = graph.indexOf(from), y = graph.indexOf(to);
        return t < 0 || y < 0 ? new java.util.LinkedHashSet<>() : graph.toIds(mediatorsBits(t, y));
    }

    /**
     * Variables {@code z}, neither an ancestor of {@code x} nor of {@code y}, with a parent
     * in {@code des[x] ∪ {x}} and a parent in {@code des[y] ∪ {y}}.
     */
    public Set<String> colliders(String x, String y) {
        int a = graph.indexOf(x), b = graph.indexOf(y);
        return a < 0 || b < 0 ? new java.util.LinkedHashSet<>() : graph.toIds(collidersBits(a, b));
    }

    /** Common ancestors of {@code x} and {@code y}, excluding both. */
    public Set<String> confounders(String x, String y) {
        int a = graph.indexOf(x), b = graph.indexOf(y);
        return a < 0 || b < 0 ? new java.util.LinkedHashSet<>() : graph.toIds(confoundersBits(a, b));
    }

    /**
     * Variables on a backdoor path from {@code treatment} to {@code outcome}: undirected paths
     * that start at a parent of {@code treatment} and reach {@code outcome} without entering
     * {@code forb(t,y) \ {y}}. Under the generalized adjustment criterion a descendant of
     * {@code t} that is not a descendant of a causal node can lie on such a path.
     */
    public Set<String> backdoorVariables(String treatment, String outcome) {
        int t = graph.indexOf(treatment), y = graph.indexOf(outcome);
        return t < 0 || y < 0 ? new java.util.LinkedHashSet<>() : graph.toIds(backdoorVariablesBits(t, y));
    }

    // ── index-level operators ──────────────────────────────────────────────

    /** {@code des[t] & anc[y]}. */
    public long[] mediatorsBits(int t, int y) {
        long[] m = BitSet64.copy(graph.descendantBits(t));
        BitSet64.and(m, graph.ancestorBits(y));
        return m;
    }

    /** {@code anc[x] & anc[y]} (neither endpoint is its own ancestor). */
    public long[] confoundersBits(int x, int y) {
        long[] c = BitSet64.copy(graph.ancestorBits(x));
        BitSet64.and(c, graph.ancestorBits(y));
        return c;
    }

    /** Causal nodes {@code cn(t,y)}: mediators plus {@code y} if {@code t} reaches {@code y}, else empty. */
    public long[] causalNodesBits(int t, int y) {
        if (t == y || !graph.reaches(t, y)) return new long[words];
        long[] cn = mediatorsBits(t, y);
        BitSet64.set(cn, y);
        return cn;
    }

    /** Forbidden set {@code {t} | cn | OR_{c in cn} des[c]}. */
    public long[] forbiddenBits(int t, int y) {
        long[] cn = causalNodesBits(t, y);
        long[] forb = BitSet64.copy(cn);
        BitSet64.stream(cn).forEach(c -> BitSet64.or(forb, graph.descendantBits(c)));
        BitSet64.set(forb, t);
        return forb;
    }

    /** {@code R(t,y) = obs & ~forb(t,y) & ~{t,y}}: the variables eligible for an adjustment set. */
    public long[] eligibleBits(int t, int y) {
        return eligible(t, y, forbiddenBits(t, y));
    }

    /** {@code R(t,y)} for a precomputed {@code forb(t,y)}. */
    long[] eligible(int t, int y, long[] forb) {
        long[] r = BitSet64.copy(observed);
        BitSet64.andNot(r, forb);
        clear(r, t);
        clear(r, y);
        return r;
    }

    /** Canonical set {@code (anc[t] | anc[y]) & R(t,y)}. */
    public long[] canonicalBits(int t, int y) {
        long[] adj = BitSet64.copy(graph.ancestorBits(t));
        BitSet64.or(adj, graph.ancestorBits(y));
        BitSet64.and(adj, eligibleBits(t, y));
        return adj;
    }

    /** Colliders between {@code x} and {@code y}, by one pass over each candidate's parents. */
    public long[] collidersBits(int x, int y) {
        long[] fromX = BitSet64.copy(graph.descendantBits(x));
        BitSet64.set(fromX, x);
        long[] fromY = BitSet64.copy(graph.descendantBits(y));
        BitSet64.set(fromY, y);
        long[] excluded = BitSet64.copy(graph.ancestorBits(x));
        BitSet64.or(excluded, graph.ancestorBits(y));
        BitSet64.set(excluded, x);
        BitSet64.set(excluded, y);

        long[] out = new long[words];
        for (int z = 0; z < graph.size(); z++) {
            if (BitSet64.get(excluded, z)) continue;
            boolean xSide = false, ySide = false;
            for (int k = 0, m = graph.parentCount(z); k < m && !(xSide && ySide); k++) {
                int p = graph.parent(z, k);
                xSide |= BitSet64.get(fromX, p);
                ySide |= BitSet64.get(fromY, p);
            }
            if (xSide && ySide) BitSet64.set(out, z);
        }
        return out;
    }

    /**
     * Backdoor variables: nodes reached by a skeleton BFS from the parents of {@code t} and by
     * one from {@code y}, never entering the barrier {@code forb(t,y) \ {y}}; {@code y} excluded.
     */
    public long[] backdoorVariablesBits(int t, int y) {
        long[] barrier = forbiddenBits(t, y);
        clear(barrier, y);
        long[] starts = new long[words];
        for (int k = 0, m = graph.parentCount(t); k < m; k++) BitSet64.set(starts, graph.parent(t, k));
        long[] fromParents = skeletonReach(starts, barrier);
        long[] fromY = new long[words];
        BitSet64.set(fromY, y);
        BitSet64.and(fromParents, skeletonReach(fromY, barrier));
        clear(fromParents, y);
        return fromParents;
    }

    /** Undirected BFS over parents and children from {@code starts}, never entering {@code barrier}. */
    private long[] skeletonReach(long[] starts, long[] barrier) {
        int n = graph.size();
        long[] seen = new long[words];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int s = 0; s < n; s++) {
            if (BitSet64.get(starts, s) && !BitSet64.get(barrier, s)) {
                BitSet64.set(seen, s);
                queue[tail++] = s;
            }
        }
        while (head < tail) {
            int u = queue[head++];
            for (int k = 0, m = graph.parentCount(u); k < m; k++) tail = visit(graph.parent(u, k), seen, barrier, queue, tail);
            for (int k = 0, m = graph.childCount(u); k < m; k++) tail = visit(graph.child(u, k), seen, barrier, queue, tail);
        }
        return seen;
    }

    private static int visit(int v, long[] seen, long[] barrier, int[] queue, int tail) {
        if (BitSet64.get(barrier, v) || BitSet64.get(seen, v)) return tail;
        BitSet64.set(seen, v);
        queue[tail] = v;
        return tail + 1;
    }

    static void clear(long[] b, int i) {
        b[i >>> 6] &= ~(1L << i);
    }
}
