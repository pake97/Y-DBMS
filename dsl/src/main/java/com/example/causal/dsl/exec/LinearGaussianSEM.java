package com.example.causal.dsl.exec;

import com.example.causal.dsl.model.CausalModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-form inference for a declared linear-Gaussian SEM over an
 * <em>already-intervened</em> graph G′ (X's incoming edges cut, {@code fixedValues}
 * set). Each variable is {@code V = intercept_V + Σ β_{P→V}·P + ε_V},
 * {@code ε_V ~ N(0, σ²_V)}.
 *
 * <p>Do-determinism needs no special casing: an intervened variable has empty
 * parents (cut by {@link CausalModel#intervene}) and is treated as zero-noise, so
 * the general mean/covariance recursion yields μ(X)=v and Var(X)=0 for free.
 *
 * <p>All answers are interventional by construction (the graph is G′). No matrix
 * library: means/variances propagate in topological order, O(n³) worst case.
 */
final class LinearGaussianSEM {

    private final CausalModel g;
    private final List<String> topo;
    private final Map<String, Double> doValues = new HashMap<>();

    LinearGaussianSEM(CausalModel g) {
        this.g = g;
        this.topo = g.topologicalOrder();
        g.fixedValues().forEach((v, s) -> doValues.put(v, Double.parseDouble(s)));
    }

    /** True when every intervened value parses as a number (else the SEM can't evaluate). */
    static boolean hasNumericInterventions(CausalModel g) {
        for (String s : g.fixedValues().values()) {
            try {
                Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Interventional means E[V | do(...)] for every variable. */
    Map<String, Double> means() {
        Map<String, Double> mu = new HashMap<>();
        for (String v : topo) {
            if (doValues.containsKey(v)) {
                mu.put(v, doValues.get(v));
                continue;
            }
            double m = g.intercept(v);
            for (String p : g.parents(v)) m += g.weight(p, v) * mu.get(p);
            mu.put(v, m);
        }
        return mu;
    }

    /** Marginal variances Var(V | do(...)); intervened variables are deterministic (0). */
    Map<String, Double> variances() {
        Map<String, Map<String, Double>> cov = new HashMap<>();
        for (String v : topo) {
            Map<String, Double> covV = new HashMap<>();
            cov.put(v, covV);
            Set<String> pv = g.parents(v);
            for (String u : topo) {
                if (u.equals(v)) {
                    double var = doValues.containsKey(v) ? 0.0 : g.noiseVar(v);
                    for (String p : pv)
                        for (String q : pv)
                            var += g.weight(p, v) * g.weight(q, v) * cov.get(p).get(q);
                    covV.put(v, var);
                    break;  // v is the last processed node; everything after is unknown
                }
                double c = 0.0;                       // Cov(v, u) = Σ β_{P→v}·Cov(P, u)
                for (String p : pv) c += g.weight(p, v) * cov.get(p).get(u);
                covV.put(u, c);
                cov.get(u).put(v, c);                 // keep the matrix symmetric
            }
        }
        Map<String, Double> var = new HashMap<>();
        for (String v : topo) var.put(v, cov.get(v).get(v));
        return var;
    }

    /**
     * Total causal effect of {@code x} on {@code y} = Σ over directed paths x→y of
     * the product of edge coefficients. Computed as a unit-impulse propagation
     * (∂x=1, ∂root=0, ∂V=Σ β·∂parent) — no path enumeration.
     */
    double totalEffect(String x, String y) {
        Map<String, Double> d = new HashMap<>();
        for (String v : topo) {
            if (v.equals(x)) {
                d.put(v, 1.0);
                continue;
            }
            double s = 0.0;
            for (String p : g.parents(v)) s += g.weight(p, v) * d.getOrDefault(p, 0.0);
            d.put(v, s);
        }
        return d.getOrDefault(y, 0.0);
    }
}
