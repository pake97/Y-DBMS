package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.*;

/**
 * Plugin-side <strong>linear-Gaussian SEM inference</strong> over the persisted,
 * compiled causal graph.
 *
 * <p>Each variable follows the structural equation
 * {@code V = intercept_V + Σ_{P∈parents(V)} β_{P→V}·P + ε_V},
 * {@code ε_V ~ N(0, σ²_V)}.
 *
 * <p>The do-operator {@code do(X=x)} fixes μ(X)=x, Var(X)=0, and conceptually
 * removes X's incoming edges. In practice, an intervened variable simply has
 * its parents skipped and noise set to zero, so the general recursion handles
 * it with no special case beyond the initial check.
 *
 * <h2>Reference oracle</h2>
 * {@code dsl/src/main/java/com/example/causal/dsl/exec/LinearGaussianSEM.java}
 * — this implementation mirrors its math exactly over the plugin's
 * {@link CompiledCausalGraph} instead of the DSL's {@code CausalModel}.
 *
 * @see PathAnalyzer the completed Tier 1 worked example this is modelled on
 */
public final class LinearGaussianInference {

    private final CompiledCausalGraph graph;

    public LinearGaussianInference(CompiledCausalGraph graph) {
        this.graph = graph;
    }

    /**
     * Interventional means {@code E[V | do(doValues)]} for every variable id.
     *
     * <p>Propagates in topological order. For each variable:
     * <ul>
     *   <li>If intervened: μ(V) = doValues.get(V)</li>
     *   <li>Otherwise: μ(V) = intercept_V + Σ_{P∈parents(V)} β_{P→V} · μ(P)</li>
     * </ul>
     */
    public Map<String, Double> interventionalMeans(Map<String, Double> doValues) {
        Map<String, Double> mu = new HashMap<>();
        for (String v : graph.topoOrder()) {
            if (doValues.containsKey(v)) {
                mu.put(v, doValues.get(v));
                continue;
            }
            double m = graph.intercept(v);
            for (String p : graph.parentsOf(v)) {
                m += graph.weight(p, v) * mu.get(p);
            }
            mu.put(v, m);
        }
        return mu;
    }

    /**
     * Marginal variances {@code Var[V | do(doValues)]}; intervened variables are
     * deterministic (variance 0).
     *
     * <p>Uses the incremental covariance recursion in topological order:
     * <ul>
     *   <li>{@code Cov(V, U) = Σ_{P∈parents(V)} β_{P→V} · Cov(P, U)}</li>
     *   <li>{@code Var(V) = Σ_P Σ_Q β_{P→V} · β_{Q→V} · Cov(P, Q) + σ²_V}</li>
     *   <li>Intervened variables: Var(V) = 0</li>
     * </ul>
     */
    public Map<String, Double> interventionalVariances(Map<String, Double> doValues) {
        List<String> topo = graph.topoOrder();
        // cov[v][u] = Cov(v, u); built incrementally in topo order
        Map<String, Map<String, Double>> cov = new HashMap<>();

        for (String v : topo) {
            Map<String, Double> covV = new HashMap<>();
            cov.put(v, covV);
            boolean intervened = doValues.containsKey(v);
            // An intervened variable has no effective parents — it is fixed and
            // independent of everything, so Cov(V, U)=0 for all U and Var(V)=0.
            Set<String> pv = intervened ? Set.of() : graph.parentsOf(v);

            for (String u : topo) {
                if (u.equals(v)) {
                    // Var(V) = σ²_V + Σ_P Σ_Q β_P · β_Q · Cov(P, Q)
                    double var = intervened ? 0.0 : graph.noiseVariance(v);
                    for (String p : pv) {
                        for (String q : pv) {
                            var += graph.weight(p, v) * graph.weight(q, v) * cov.get(p).get(q);
                        }
                    }
                    covV.put(v, var);
                    break; // v is the last processed node; everything after is unknown
                }
                // Cov(V, U) = Σ_{P∈parents(V)} β_{P→V} · Cov(P, U)
                double c = 0.0;
                for (String p : pv) {
                    c += graph.weight(p, v) * cov.get(p).get(u);
                }
                covV.put(u, c);
                cov.get(u).put(v, c); // keep the matrix symmetric
            }
        }

        // Extract diagonal (marginal variances)
        Map<String, Double> var = new HashMap<>();
        for (String v : topo) {
            var.put(v, cov.get(v).get(v));
        }
        return var;
    }

    /**
     * Total causal effect of {@code treatment} on {@code outcome} — the sum over
     * all directed paths of the product of edge coefficients.
     *
     * <p>Computed via unit-impulse propagation (no path enumeration):
     * <ul>
     *   <li>∂treatment = 1</li>
     *   <li>∂V = Σ_{P∈parents(V)} β_{P→V} · ∂P  (for V ≠ treatment)</li>
     *   <li>Result = ∂outcome</li>
     * </ul>
     */
    public double totalEffect(String treatment, String outcome) {
        Map<String, Double> d = new HashMap<>();
        for (String v : graph.topoOrder()) {
            if (v.equals(treatment)) {
                d.put(v, 1.0);
                continue;
            }
            double s = 0.0;
            for (String p : graph.parentsOf(v)) {
                s += graph.weight(p, v) * d.getOrDefault(p, 0.0);
            }
            d.put(v, s);
        }
        return d.getOrDefault(outcome, 0.0);
    }
}
