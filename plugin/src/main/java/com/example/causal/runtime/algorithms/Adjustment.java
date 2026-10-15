package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Identification of the total effect of {@code t} on {@code y} by covariate adjustment under
 * the generalized adjustment criterion (Shpitser, VanderWeele and Robins 2010; Perković et
 * al. 2018), the criterion dagitty implements (cost tier (c) of the paper).
 *
 * <p>With {@code cn}, {@code forb} and {@code R} as in {@link PathAnalyzer}: {@code Z} is an
 * adjustment set iff {@code Z ∩ forb(t,y) = ∅} and {@code t ⊥ y | Z} in the proper backdoor
 * graph {@code G_pbd(t,y)}, i.e. {@code G} without the edges {@code t→c} for {@code c ∈ cn(t,y)}.
 * The effect is identifiable by adjustment iff the canonical set {@code adj(t,y)} is one
 * (constructive criterion, van der Zander, Liśkiewicz and Textor 2019): one canonical-set
 * construction plus one masked Bayes-Ball run.
 */
public final class Adjustment {

    /** The decision and the two sets it was made from (dense-index bitsets). */
    public record Decision(boolean identifiable, long[] canonical, long[] forbidden) {}

    private Adjustment() {}

    /** True iff {@code z} satisfies the generalized adjustment criterion for {@code (t, y)}. */
    public static boolean isValid(CompiledCausalGraph g, int t, int y, long[] z) {
        return isValid(g, new PathAnalyzer(g), t, y, z);
    }

    static boolean isValid(CompiledCausalGraph g, PathAnalyzer pa, int t, int y, long[] z) {
        if (BitSet64.intersects(z, pa.forbiddenBits(t, y))) return false;
        return separatedPbd(g, pa, t, y, z);
    }

    /**
     * Constructive criterion: identifiable iff {@code adj(t,y)} is an adjustment set.
     * {@code t == y}: identifiable with the empty set. If {@code t} does not reach {@code y},
     * {@code cn} is empty, {@code forb = {t}}, and the decision is still computed (the effect
     * is zero but the criterion is well defined).
     */
    public static Decision decide(CompiledCausalGraph g, int t, int y) {
        PathAnalyzer pa = new PathAnalyzer(g);
        long[] forb = pa.forbiddenBits(t, y);
        if (t == y) return new Decision(true, new long[g.wordCount()], forb);
        long[] adj = pa.canonicalBits(t, y);
        return new Decision(isValid(g, pa, t, y, adj), adj, forb);
    }

    /** {@code t ⊥ y | z} in {@code G_pbd(t,y)}: the outgoing edges of {@code t} into {@code cn(t,y)} are cut. */
    static boolean separatedPbd(CompiledCausalGraph g, PathAnalyzer pa, int t, int y, long[] z) {
        long[] tb = DSeparation.bits(g, t), yb = DSeparation.bits(g, y);
        return DSeparation.separated(g, tb, yb, z, tb, pa.causalNodesBits(t, y));
    }

    // ── minimal adjustment sets: separator framework of van der Zander et al. (2019) ──

    /**
     * Streams the inclusion-minimal adjustment sets of {@code (t, y)}: the minimal separators of
     * {@code t} and {@code y} in {@code G_pbd(t,y)} within {@code R(t,y)} (observed, not
     * forbidden, not an endpoint). Stops after {@code limit} sets ({@code limit <= 0}: no limit).
     *
     * <p>Algorithm {@code ListMinSep} of van der Zander, Liśkiewicz and Textor (JAIR 2019), i.e.
     * Takata's close-separator branching (2010) on the moral graph of {@code G_pbd[An(t ∪ y)]}
     * with the non-eligible vertices eliminated (see {@link MoralSeparators}). Every emitted set
     * is inclusion-minimal by construction, no set is emitted twice, and every explored branch
     * contains a solution, so at most {@code |R| + 1} {@code findMinSep} calls (each
     * {@code O(|V| + |E|)} on the reduced graph) separate two emitted sets: polynomial delay
     * regardless of the number of answers.
     */
    public static void listMinimal(CompiledCausalGraph g, int t, int y, int limit, Consumer<long[]> emit) {
        listMinimal(g, t, y, limit, emit, () -> { });
    }

    /** As above; {@code onFindMinSep} runs at every oracle call (the delay test counts them). */
    static void listMinimal(CompiledCausalGraph g, int t, int y, int limit, Consumer<long[]> emit,
                            Runnable onFindMinSep) {
        if (t == y) {
            emit.accept(new long[g.wordCount()]);
            return;
        }
        new MoralSeparators(g, t, y, onFindMinSep).listMinSep(limit <= 0 ? Integer.MAX_VALUE : limit, emit);
    }

    /** One inclusion-minimal adjustment set ({@code findMinSep(∅, R)}), or empty if none exists. */
    public static Optional<long[]> findMinimal(CompiledCausalGraph g, int t, int y) {
        if (t == y) return Optional.of(new long[g.wordCount()]);
        Separators s = new Separators(g, t, y);
        return Optional.ofNullable(s.findMinSep(new long[g.wordCount()], s.r));
    }

    /** findSep / findMinSep (constraint sets I ⊆ Z ⊆ R) for one (t, y), on the proper backdoor graph. */
    private static final class Separators {
        final CompiledCausalGraph g;
        final PathAnalyzer pa;
        final int t, y, n;
        final long[] cn, r, ancTY, tb, yb;

        Separators(CompiledCausalGraph g, int t, int y) {
            this.g = g;
            this.pa = new PathAnalyzer(g);
            this.t = t;
            this.y = y;
            this.n = g.size();
            this.cn = pa.causalNodesBits(t, y);
            this.r = pa.eligibleBits(t, y);
            this.ancTY = BitSet64.copy(g.ancestorBits(t));
            BitSet64.or(ancTY, g.ancestorBits(y));
            this.tb = DSeparation.bits(g, t);
            this.yb = DSeparation.bits(g, y);
        }

        /**
         * {@code Z = An(t ∪ y ∪ I) ∩ R}; if any separator {@code Z} with {@code I ⊆ Z ⊆ R} exists,
         * this one is (van der Zander et al. 2019). Null if it does not separate.
         */
        long[] findSep(long[] i, long[] rr) {
            long[] z = BitSet64.copy(ancTY);
            BitSet64.or(z, i);
            BitSet64.stream(i).forEach(v -> BitSet64.or(z, g.ancestorBits(v)));
            BitSet64.and(z, rr);
            PathAnalyzer.clear(z, t);
            PathAnalyzer.clear(z, y);
            return DSeparation.separated(g, tb, yb, z, tb, cn) ? z : null;
        }

        /**
         * A separator {@code Z}, {@code I ⊆ Z ⊆ R}, from which no variable outside {@code I} can be
         * removed. All such {@code Z} share the ancestral set {@code A = An(t ∪ y ∪ I)}, so
         * d-separation is vertex separation in the moral graph of {@code G_pbd[A]}: keep the
         * candidates reachable from {@code t}, then those of them reachable from {@code y}, each
         * time treating the current candidates (including {@code I}) as blocking.
         */
        long[] findMinSep(long[] i, long[] rr) {
            long[] zp = findSep(i, rr);
            if (zp == null) return null;
            long[] a = DSeparation.ancestralMask(g, tb, yb, zp);
            long[] z1 = moralReach(t, zp, a);
            BitSet64.and(z1, zp);
            BitSet64.or(z1, i);
            long[] z = moralReach(y, z1, a);
            BitSet64.and(z, z1);
            BitSet64.or(z, i);
            return z;
        }

        /**
         * Nodes reached by BFS from {@code start} in the moral graph of {@code G_pbd[A]}: a node of
         * {@code blocked} is reached but not expanded. The moral graph is not materialized:
         * neighbors are parents, children and co-parents within {@code A}, minus the cut edges
         * {@code t→c}, {@code c ∈ cn}.
         */
        long[] moralReach(int start, long[] blocked, long[] a) {
            long[] seen = new long[g.wordCount()];
            int[] queue = new int[n];
            int head = 0, tail = 0;
            BitSet64.set(seen, start);
            queue[tail++] = start;
            while (head < tail) {
                int u = queue[head++];
                if (u != start && BitSet64.get(blocked, u)) continue;
                for (int k = 0, m = g.parentCount(u); k < m; k++) {
                    int p = g.parent(u, k);
                    if (!cut(p, u)) tail = visit(p, a, seen, queue, tail);
                }
                for (int k = 0, m = g.childCount(u); k < m; k++) {
                    int c = g.child(u, k);
                    if (cut(u, c) || !BitSet64.get(a, c)) continue;
                    tail = visit(c, a, seen, queue, tail);
                    for (int j = 0, mm = g.parentCount(c); j < mm; j++) {
                        int q = g.parent(c, j);
                        if (q != u && !cut(q, c)) tail = visit(q, a, seen, queue, tail);
                    }
                }
            }
            return seen;
        }

        /** Edge {@code from→to} is removed in {@code G_pbd(t,y)}. */
        private boolean cut(int from, int to) {
            return from == t && BitSet64.get(cn, to);
        }

        private static int visit(int v, long[] a, long[] seen, int[] queue, int tail) {
            if (!BitSet64.get(a, v) || BitSet64.get(seen, v)) return tail;
            BitSet64.set(seen, v);
            queue[tail] = v;
            return tail + 1;
        }
    }

    /**
     * Minimal {@code (t,y)}-separators within {@code R} as an undirected problem, for
     * {@code listMinSep} (van der Zander, Liśkiewicz and Textor, JAIR 2019; Takata 2010).
     *
     * <p>With {@code I = ∅} every candidate lies in {@code A0 = An(t ∪ y)}, which is ancestral in
     * {@code G_pbd} too, so for {@code Z ⊆ A0} d-separation in {@code G_pbd} is vertex separation
     * in {@code H}, the moral graph of {@code G_pbd[A0]} (Lauritzen et al. 1990). Variables of
     * {@code A0} that may not be adjusted for ({@code forb}, latents) are eliminated from
     * {@code H} (their neighbourhood made a clique), which preserves {@code t}–{@code y}
     * separation for every set made of eligible variables. The minimal adjustment sets are
     * then exactly the minimal {@code t}–{@code y} separators of the reduced graph {@code H'}.
     *
     * <p>Takata's branching: a state {@code (C, X)} asks for the minimal separators whose
     * {@code t}-side contains {@code C} and avoids {@code X}. The oracle {@code findMinSep(C, X)}
     * returns the separator closest to {@code C}: {@code D} = component of {@code y} in
     * {@code H' − N[C]}, {@code S0 = N(D)}, {@code T0} = component of {@code t} in
     * {@code H' − S0}. {@code T0} is contained in the {@code t}-side of every separator whose
     * {@code t}-side contains {@code C}, so a solution exists iff {@code T0 ∩ X = ∅}. Branch on
     * {@code v ∈ S0 \ X}: {@code v} on the {@code t}-side (oracle call) or excluded (contains
     * {@code S0}, no call). A state with {@code S0 \ X = ∅} emits {@code S0}.
     */
    static final class MoralSeparators {
        private final CompiledCausalGraph g;
        private final int t, y, words;
        private final Runnable onFindMinSep;
        /** Adjacency rows of H' (null outside H'); H' = (eligible ∩ A0) ∪ {t, y}. */
        private final long[][] adj;
        private final long[] vertices;

        MoralSeparators(CompiledCausalGraph g, int t, int y, Runnable onFindMinSep) {
            this.g = g;
            this.t = t;
            this.y = y;
            this.words = g.wordCount();
            this.onFindMinSep = onFindMinSep;
            PathAnalyzer pa = new PathAnalyzer(g);
            long[] cn = pa.causalNodesBits(t, y);
            long[] a0 = BitSet64.copy(g.ancestorBits(t));
            BitSet64.or(a0, g.ancestorBits(y));
            BitSet64.set(a0, t);
            BitSet64.set(a0, y);

            // moral graph of G_pbd[A0]: skeleton plus co-parent edges, without the edges t -> cn
            adj = new long[g.size()][];
            BitSet64.stream(a0).forEach(v -> adj[v] = new long[words]);
            BitSet64.stream(a0).forEach(c -> {
                int[] ps = new int[g.parentCount(c)];
                int np = 0;
                for (int k = 0; k < ps.length; k++) {
                    int p = g.parent(c, k);
                    if (BitSet64.get(a0, p) && !(p == t && BitSet64.get(cn, c))) ps[np++] = p;
                }
                for (int a = 0; a < np; a++) {
                    link(ps[a], c);
                    for (int b = a + 1; b < np; b++) link(ps[a], ps[b]);
                }
            });

            // eliminate the vertices of A0 that may not be in an adjustment set
            long[] keep = pa.eligibleBits(t, y);
            BitSet64.and(keep, a0);
            BitSet64.set(keep, t);
            BitSet64.set(keep, y);
            long[] eliminate = BitSet64.copy(a0);
            BitSet64.andNot(eliminate, keep);
            BitSet64.stream(eliminate).forEach(u -> {
                long[] nu = adj[u];
                BitSet64.stream(nu).forEach(w -> {
                    BitSet64.or(adj[w], nu);
                    PathAnalyzer.clear(adj[w], w);
                    PathAnalyzer.clear(adj[w], u);
                });
                adj[u] = null;
            });
            this.vertices = keep;
        }

        private void link(int a, int b) {
            BitSet64.set(adj[a], b);
            BitSet64.set(adj[b], a);
        }

        /** Closest separator to {@code c}, or null: {@code {S0, T0}} as described above. */
        long[][] findMinSep(long[] c, long[] x) {
            onFindMinSep.run();
            long[] closedC = BitSet64.copy(c);
            BitSet64.stream(c).forEach(v -> BitSet64.or(closedC, adj[v]));
            if (BitSet64.get(closedC, y)) return null;
            long[] d = component(y, closedC);
            long[] s0 = new long[words];
            BitSet64.stream(d).forEach(v -> BitSet64.or(s0, adj[v]));
            BitSet64.andNot(s0, d);
            long[] t0 = component(t, s0);
            return BitSet64.intersects(t0, x) ? null : new long[][]{s0, t0};
        }

        void listMinSep(int limit, Consumer<long[]> emit) {
            long[] c = DSeparation.bits(g, t);
            long[] x = new long[words];
            long[][] st = findMinSep(c, x);
            if (st != null) list(st, x, limit, emit, new int[1]);
        }

        /** {@code st = {S0, T0}} of the current state; the state's {@code C} is replaced by {@code T0}. */
        private void list(long[][] st, long[] x, int limit, Consumer<long[]> emit, int[] emitted) {
            if (emitted[0] >= limit) return;
            long[] s0 = st[0], t0 = st[1];
            long[] free = BitSet64.copy(s0);
            BitSet64.andNot(free, x);
            int v = BitSet64.stream(free).findFirst().orElse(-1);
            if (v < 0) {
                emitted[0]++;
                emit.accept(BitSet64.copy(s0));
                return;
            }
            long[] withV = BitSet64.copy(t0);
            BitSet64.set(withV, v);
            long[][] in = findMinSep(withV, x);
            if (in != null) list(in, x, limit, emit, emitted);
            long[] xv = BitSet64.copy(x);
            BitSet64.set(xv, v);
            list(st, xv, limit, emit, emitted);   // S0 itself is a solution here: no oracle call
        }

        /** Component of {@code start} in {@code H' − blocked}. */
        private long[] component(int start, long[] blocked) {
            long[] seen = new long[words];
            int[] queue = new int[g.size()];
            int head = 0, tail = 0;
            BitSet64.set(seen, start);
            queue[tail++] = start;
            while (head < tail) {
                int u = queue[head++];
                long[] row = adj[u];
                for (int w = 0; w < words; w++) {
                    long bits = row[w] & ~seen[w] & ~blocked[w];
                    while (bits != 0) {
                        int v = (w << 6) + Long.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        seen[w] |= 1L << v;
                        queue[tail++] = v;
                    }
                }
            }
            return seen;
        }
    }
}
