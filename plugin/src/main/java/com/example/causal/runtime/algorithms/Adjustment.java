package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.index.BitSet64;

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
}
