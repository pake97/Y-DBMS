package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for {@link LinearGaussianInference} and
 * {@link CounterfactualInference}. No Neo4j required.
 *
 * <p>All expected values are taken from the EXERCISES.md oracle and verified
 * against the DSL's {@code LinearGaussianSEM} / {@code CounterfactualEvaluator}.
 */
class LinearGaussianInferenceTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Chain graph: X→M (β=2), M→Y (β=3)
    //   all intercepts 0.0, all noise 1.0
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ChainGraph {

        private final CompiledCausalGraph g = chainGraph();

        // ── totalEffect ─────────────────────────────────────────────────

        @Test
        void totalEffect_X_to_Y_is_6() {
            // β(X→M)·β(M→Y) = 2·3 = 6
            assertEquals(6.0, engine().totalEffect("X", "Y"), 1e-9);
        }

        @Test
        void totalEffect_X_to_M_is_2() {
            assertEquals(2.0, engine().totalEffect("X", "M"), 1e-9);
        }

        @Test
        void totalEffect_M_to_Y_is_3() {
            assertEquals(3.0, engine().totalEffect("M", "Y"), 1e-9);
        }

        @Test
        void totalEffect_Y_to_X_is_0() {
            // no reverse path
            assertEquals(0.0, engine().totalEffect("Y", "X"), 1e-9);
        }

        // ── interventionalMeans ─────────────────────────────────────────

        @Test
        void means_doX4_gives_EY24() {
            // E[Y|do(X=4)] = 3·(2·4) = 24
            Map<String, Double> mu = engine().interventionalMeans(Map.of("X", 4.0));
            assertEquals(4.0, mu.get("X"), 1e-9);
            assertEquals(8.0, mu.get("M"), 1e-9);  // 2·4
            assertEquals(24.0, mu.get("Y"), 1e-9); // 3·8
        }

        @Test
        void means_doX1_gives_EY6() {
            // E[Y|do(X=1)] = 3·(2·1) = 6
            Map<String, Double> mu = engine().interventionalMeans(Map.of("X", 1.0));
            assertEquals(6.0, mu.get("Y"), 1e-9);
        }

        @Test
        void means_noIntervention_allZero() {
            // No do(), all intercepts 0 → all means 0
            Map<String, Double> mu = engine().interventionalMeans(Map.of());
            assertEquals(0.0, mu.get("X"), 1e-9);
            assertEquals(0.0, mu.get("M"), 1e-9);
            assertEquals(0.0, mu.get("Y"), 1e-9);
        }

        // ── interventionalVariances ─────────────────────────────────────

        @Test
        void variances_doX1_gives_VarY10() {
            // do(X=1) ⇒ Var(X)=0, Var(M) = 2²·0 + 1 = 1,
            //            Var(Y) = 3²·1 + 1 = 10
            Map<String, Double> var = engine().interventionalVariances(Map.of("X", 1.0));
            assertEquals(0.0, var.get("X"), 1e-9);
            assertEquals(1.0, var.get("M"), 1e-9);
            assertEquals(10.0, var.get("Y"), 1e-9);
        }

        @Test
        void variances_noIntervention() {
            // Var(X)=1, Var(M)=2²·1+1=5, Var(Y)=3²·5+1=46
            Map<String, Double> var = engine().interventionalVariances(Map.of());
            assertEquals(1.0, var.get("X"), 1e-9);
            assertEquals(5.0, var.get("M"), 1e-9);
            assertEquals(46.0, var.get("Y"), 1e-9);
        }

        private LinearGaussianInference engine() {
            return new LinearGaussianInference(g);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Confounded fork: Z→X (β=5), Z→Y (β=7), X→Y (β=2)
    //   all intercepts 0.0, all noise 1.0
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ConfoundedForkGraph {

        private final CompiledCausalGraph g = confoundedForkGraph();

        @Test
        void totalEffect_X_to_Y_is_2() {
            // do() cuts Z→X, so only direct X→Y remains ⇒ β=2
            assertEquals(2.0, engine().totalEffect("X", "Y"), 1e-9);
        }

        @Test
        void totalEffect_Z_to_Y() {
            // Z→Y (direct, β=7) + Z→X→Y (5·2=10) = 17
            assertEquals(17.0, engine().totalEffect("Z", "Y"), 1e-9);
        }

        @Test
        void means_doX3_gives_EY6() {
            // do(X=3): Z is free (mean=0), Y = 7·Z + 2·X = 7·0 + 2·3 = 6
            Map<String, Double> mu = engine().interventionalMeans(Map.of("X", 3.0));
            assertEquals(6.0, mu.get("Y"), 1e-9);
        }

        @Test
        void variances_doX3_gives_VarY50() {
            // do(X=3): Var(Z)=1, Var(X)=0
            // Var(Y) = 7²·Var(Z) + 2²·Var(X) + 2·7·2·Cov(Z,X) + σ²_Y
            // Cov(Z,X) = 0 (X is intervened, independent of Z)
            // Var(Y) = 49·1 + 0 + 0 + 1 = 50
            Map<String, Double> var = engine().interventionalVariances(Map.of("X", 3.0));
            assertEquals(50.0, var.get("Y"), 1e-9);
        }

        private LinearGaussianInference engine() {
            return new LinearGaussianInference(g);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Counterfactual inference
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class Counterfactual {

        @Test
        void chain_givenX1_Y10_ifX3_then_Ycf22() {
            // EXERCISES.md: chain (β=2,3), GIVEN X=1, Y=10, IF X=3 ⇒ Y_cf = 10 + 6·(3−1) = 22
            CompiledCausalGraph g = chainGraph();
            CounterfactualInference cf = new CounterfactualInference(g);

            double yCf = cf.counterfactual("X", 3.0, "Y",
                    Map.of("X", 1.0, "Y", 10.0));

            assertEquals(22.0, yCf, 1e-9);
        }

        @Test
        void sameIntervention_returnsFactualValue() {
            // If x* = X_factual, Y_cf = Y_factual (no change)
            CompiledCausalGraph g = chainGraph();
            CounterfactualInference cf = new CounterfactualInference(g);

            double yCf = cf.counterfactual("X", 1.0, "Y",
                    Map.of("X", 1.0, "Y", 10.0));

            assertEquals(10.0, yCf, 1e-9);
        }

        @Test
        void missingFactualForIntervention_throws() {
            CompiledCausalGraph g = chainGraph();
            CounterfactualInference cf = new CounterfactualInference(g);

            assertThrows(IllegalArgumentException.class,
                    () -> cf.counterfactual("X", 3.0, "Y", Map.of("Y", 10.0)));
        }

        @Test
        void missingFactualForOutcome_throws() {
            CompiledCausalGraph g = chainGraph();
            CounterfactualInference cf = new CounterfactualInference(g);

            assertThrows(IllegalArgumentException.class,
                    () -> cf.counterfactual("X", 3.0, "Y", Map.of("X", 1.0)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void singleNode_noParents_meansEqualsIntercept() {
            CompiledCausalGraph g = singleNodeGraph(5.0, 2.0);
            Map<String, Double> mu = new LinearGaussianInference(g).interventionalMeans(Map.of());
            assertEquals(5.0, mu.get("V"), 1e-9);
        }

        @Test
        void singleNode_varianceEqualsNoise() {
            CompiledCausalGraph g = singleNodeGraph(5.0, 2.0);
            Map<String, Double> var = new LinearGaussianInference(g).interventionalVariances(Map.of());
            assertEquals(2.0, var.get("V"), 1e-9);
        }

        @Test
        void totalEffect_sameNode_is1() {
            CompiledCausalGraph g = chainGraph();
            assertEquals(1.0, new LinearGaussianInference(g).totalEffect("X", "X"), 1e-9);
        }

        @Test
        void totalEffect_unconnectedNodes_is0() {
            CompiledCausalGraph g = disconnectedGraph();
            assertEquals(0.0, new LinearGaussianInference(g).totalEffect("A", "B"), 1e-9);
        }
    }

    // =====================================================================
    // Test graph builders (reuse the pattern from LinearGaussianDataTest)
    // =====================================================================

    /** Chain: X→M (β=2), M→Y (β=3). All intercepts 0.0, all noise 1.0. */
    private static CompiledCausalGraph chainGraph() {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("X", Variable.from("X", "X", true, false, 0.0, 1.0));
        vars.put("M", Variable.from("M", "M", true, false, 0.0, 1.0));
        vars.put("Y", Variable.from("Y", "Y", true, false, 0.0, 1.0));

        Map<String, Map<String, Double>> weights = new LinkedHashMap<>();
        weights.put("M", Map.of("X", 2.0));
        weights.put("Y", Map.of("M", 3.0));

        return buildGraph(vars, List.of("X->M", "M->Y"), weights);
    }

    /** Confounded fork: Z→X (β=5), Z→Y (β=7), X→Y (β=2). */
    private static CompiledCausalGraph confoundedForkGraph() {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("Z", Variable.from("Z", "Z", true, false, 0.0, 1.0));
        vars.put("X", Variable.from("X", "X", true, false, 0.0, 1.0));
        vars.put("Y", Variable.from("Y", "Y", true, false, 0.0, 1.0));

        Map<String, Map<String, Double>> weights = new LinkedHashMap<>();
        weights.put("X", Map.of("Z", 5.0));
        weights.put("Y", new LinkedHashMap<>(Map.of("Z", 7.0, "X", 2.0)));

        return buildGraph(vars, List.of("Z->X", "Z->Y", "X->Y"), weights);
    }

    /** Single isolated variable with custom intercept and noise. */
    private static CompiledCausalGraph singleNodeGraph(double intercept, double noise) {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("V", Variable.from("V", "V", true, false, intercept, noise));
        return buildGraph(vars, List.of(), Map.of());
    }

    /** Two disconnected nodes A and B. */
    private static CompiledCausalGraph disconnectedGraph() {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("A", Variable.from("A", "A", true, false, 0.0, 1.0));
        vars.put("B", Variable.from("B", "B", true, false, 0.0, 1.0));
        return buildGraph(vars, List.of(), Map.of());
    }

    // ── shared builder (reflection into package-private constructor) ─────

    private static CompiledCausalGraph buildGraph(
            Map<String, Variable> vars,
            List<String> edges,
            Map<String, Map<String, Double>> edgeWeights) {

        Map<String, Set<String>> forward = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();

        vars.keySet().forEach(id -> {
            forward.putIfAbsent(id, new LinkedHashSet<>());
            reverse.putIfAbsent(id, new LinkedHashSet<>());
        });

        edges.stream()
                .map(e -> e.split("->"))
                .forEach(parts -> {
                    forward.computeIfAbsent(parts[0], k -> new LinkedHashSet<>()).add(parts[1]);
                    reverse.computeIfAbsent(parts[1], k -> new LinkedHashSet<>()).add(parts[0]);
                });

        List<String> topoOrder = TopologicalSorter.sort(vars.keySet(), forward);

        try {
            Constructor<CompiledCausalGraph> ctor = CompiledCausalGraph.class.getDeclaredConstructor(
                    Map.class, Map.class, Map.class, List.class, Map.class, Instant.class
            );
            ctor.setAccessible(true);
            return ctor.newInstance(vars, forward, reverse, topoOrder, edgeWeights, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to build test compiled graph", e);
        }
    }
}
