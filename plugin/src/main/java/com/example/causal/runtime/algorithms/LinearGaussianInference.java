package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;

import java.util.Map;
import java.util.Set;

/**
 * Plugin-side <strong>linear-Gaussian SEM inference</strong> over the persisted,
 * compiled causal graph.
 *
 * <h2>Status: Tier 2 student exercise — NOT yet implemented</h2>
 * Every method below throws {@link UnsupportedOperationException}. This class has
 * no callers yet; it is a placeholder marking where the plugin's quantitative
 * runtime goes. Implementing it (and the contract around it) lets the DSL's
 * {@code Neo4jQueryExecutor} answer numeric {@code INTERVENE … RETURN
 * DISTRIBUTION/EFFECT/EXPECTATION} against the live graph instead of only the
 * in-memory path.
 *
 * <h2>Reference oracle</h2>
 * The DSL already ships a complete, tested reference implementation:
 * {@code dsl/src/main/java/com/example/causal/dsl/exec/LinearGaussianSEM.java}
 * (with cases in {@code CausalDSLTest}). Treat it as the correctness oracle —
 * the same closed-form math, just over the plugin's {@link CompiledCausalGraph}
 * instead of the DSL's {@code CausalModel}. Each variable is
 * {@code V = intercept_V + Σ_{P∈parents(V)} β_{P→V}·P + ε_V}, {@code ε_V ~ N(0, σ²_V)}.
 *
 * <h2>Suggested steps</h2>
 * <ol>
 *   <li><b>Schema + loader.</b> The compiled graph carries no parameters today.
 *       Decide where they live — e.g. {@code :Variable {intercept, noise}} and
 *       {@code :CAUSES {weight}} — and load them in {@code GraphLoader} into
 *       {@link CompiledCausalGraph} (add accessors mirroring {@code parentsOf}).</li>
 *   <li><b>Engine.</b> Implement the three methods below. Means propagate in
 *       {@code topoOrder()}; variances via the incremental covariance recursion
 *       {@code Cov(V,U)=Σ β·Cov(P,U)}, {@code Var(V)=ΣΣ β·β·Cov(P,Q)+σ²_V};
 *       total effect via a unit-impulse propagation (∂x=1). See the oracle.</li>
 *   <li><b>Contract + procedures.</b> Design the {@code api.*} surface (a
 *       {@code CausalGraphRuntime} method + DTO + {@code ProcedureNames} entry)
 *       and a {@code causal.*} procedure so the DSL can call this over Bolt.
 *       This is yours to shape — keep it on the {@code com.example.causal.api}
 *       boundary the DSL depends on.</li>
 *   <li><b>Do-operator.</b> An intervention {@code do(X=x)} fixes μ(X)=x and
 *       Var(X)=0 and removes X's incoming edges. Reuse the structural surgery;
 *       in the oracle, a fixed variable simply has empty parents and zero noise,
 *       so the general recursion yields the interventional answer for free.</li>
 * </ol>
 *
 * @see PathAnalyzer the completed Tier 1 worked example to model this on
 */
public final class LinearGaussianInference {

    private final CompiledCausalGraph graph;

    public LinearGaussianInference(CompiledCausalGraph graph) {
        this.graph = graph;
    }

    /**
     * Interventional means {@code E[V | do(doValues)]} for every variable id.
     * TODO(student, tier 2): implement — propagate in {@code graph.topoOrder()}.
     */
    public Map<String, Double> interventionalMeans(Map<String, Double> doValues) {
        throw new UnsupportedOperationException(
            "TODO(student, tier 2): linear-Gaussian means — see dsl LinearGaussianSEM oracle");
    }

    /**
     * Marginal variances {@code Var[V | do(doValues)]}; intervened variables are
     * deterministic (0).
     * TODO(student, tier 2): implement the incremental covariance recursion.
     */
    public Map<String, Double> interventionalVariances(Map<String, Double> doValues) {
        throw new UnsupportedOperationException(
            "TODO(student, tier 2): linear-Gaussian variances — see dsl LinearGaussianSEM oracle");
    }

    /**
     * Total causal effect of {@code treatment} on {@code outcome} — the sum over
     * directed paths of the product of edge coefficients.
     * TODO(student, tier 2): implement via unit-impulse propagation (∂treatment=1).
     */
    public double totalEffect(String treatment, String outcome) {
        throw new UnsupportedOperationException(
            "TODO(student, tier 2): linear-Gaussian total effect — see dsl LinearGaussianSEM oracle");
    }

    @SuppressWarnings("unused") // referenced once the methods above are implemented
    private Set<String> parentsOf(String id) {
        return graph.parentsOf(id);
    }
}
