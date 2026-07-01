package com.example.causal.runtime.algorithms;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for the linear-Gaussian SEM data structures and accessors
 * on {@link CompiledCausalGraph}. No Neo4j required.
 *
 * <p>Uses the same reflection-based construction as {@link PathAnalyzerTest}.
 */
class LinearGaussianDataTest {

    // ── Chain graph from EXERCISES.md: X→M (β=2), M→Y (β=3) ────────────

    @Test
    void chain_weightAccessorsReturnDeclaredValues() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(2.0, g.weight("X", "M"));
        assertEquals(3.0, g.weight("M", "Y"));
    }

    @Test
    void chain_interceptAccessorsReturnDeclaredValues() {
        CompiledCausalGraph g = chainGraph();
        // All intercepts explicitly set to 0.0 in this graph
        assertEquals(0.0, g.intercept("X"));
        assertEquals(0.0, g.intercept("M"));
        assertEquals(0.0, g.intercept("Y"));
    }

    @Test
    void chain_noiseVarianceAccessorsReturnDeclaredValues() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(1.0, g.noiseVariance("X"));
        assertEquals(1.0, g.noiseVariance("M"));
        assertEquals(1.0, g.noiseVariance("Y"));
    }

    @Test
    void chain_isParameterizedReturnsTrueWhenWeightsSetToNonDefault() {
        CompiledCausalGraph g = chainGraph();
        assertTrue(g.isParameterized(), "graph with β≠1 should be parameterized");
    }

    // ── Confounded fork: Z→X (β=5), Z→Y (β=7), X→Y (β=2) ──────────────

    @Test
    void fork_weightAccessorsReturnDeclaredValues() {
        CompiledCausalGraph g = confoundedForkGraph();
        assertEquals(5.0, g.weight("Z", "X"));
        assertEquals(7.0, g.weight("Z", "Y"));
        assertEquals(2.0, g.weight("X", "Y"));
    }

    @Test
    void fork_isParameterizedReturnsTrue() {
        assertTrue(confoundedForkGraph().isParameterized());
    }

    // ── Default values ──────────────────────────────────────────────────

    @Test
    void defaultWeight_returnsOneForUndeclaredEdge() {
        CompiledCausalGraph g = structuralOnlyGraph();
        // Edge A→B exists but has no explicit weight
        assertEquals(1.0, g.weight("A", "B"));
    }

    @Test
    void defaultIntercept_returnsZeroForUndeclaredVariable() {
        CompiledCausalGraph g = structuralOnlyGraph();
        assertEquals(0.0, g.intercept("A"));
    }

    @Test
    void defaultNoiseVariance_returnsOneForUndeclaredVariable() {
        CompiledCausalGraph g = structuralOnlyGraph();
        assertEquals(1.0, g.noiseVariance("A"));
    }

    @Test
    void structuralOnlyGraph_isParameterizedReturnsFalse() {
        assertFalse(structuralOnlyGraph().isParameterized(),
                "graph with all-default SEM params should not be parameterized");
    }

    @Test
    void weightForNonExistentEdge_returnsDefault() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(1.0, g.weight("X", "Y"), "no direct edge X→Y");
    }

    @Test
    void interceptForUnknownVariable_returnsDefault() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(0.0, g.intercept("UNKNOWN"));
    }

    @Test
    void noiseVarianceForUnknownVariable_returnsDefault() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(1.0, g.noiseVariance("UNKNOWN"));
    }

    // ── Non-default intercept/noise makes graph parameterized ───────────

    @Test
    void customIntercept_makesGraphParameterized() {
        // Single variable with intercept=5.0, otherwise default
        CompiledCausalGraph g = singleVariableGraph(5.0, Variable.DEFAULT_NOISE_VARIANCE);
        assertTrue(g.isParameterized(), "non-default intercept should flag parameterized");
        assertEquals(5.0, g.intercept("V"));
    }

    @Test
    void customNoise_makesGraphParameterized() {
        CompiledCausalGraph g = singleVariableGraph(Variable.DEFAULT_INTERCEPT, 2.5);
        assertTrue(g.isParameterized(), "non-default noise should flag parameterized");
        assertEquals(2.5, g.noiseVariance("V"));
    }

    // ── Existing structural accessors still work ────────────────────────

    @Test
    void chain_structuralAccessorsUnaffected() {
        CompiledCausalGraph g = chainGraph();
        assertEquals(Set.of("M"), g.childrenOf("X"));
        assertEquals(Set.of("X"), g.parentsOf("M"));
        assertEquals(List.of("X", "M", "Y"), g.topoOrder());
        assertEquals(3, g.size());
    }

    // =====================================================================
    // Test graph builders
    // =====================================================================

    /**
     * Chain: X→M (β=2), M→Y (β=3). All intercepts 0.0, all noise 1.0.
     */
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

    /**
     * Confounded fork: Z→X (β=5), Z→Y (β=7), X→Y (β=2).
     */
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

    /** Structural-only graph (no SEM params): A→B→C. */
    private static CompiledCausalGraph structuralOnlyGraph() {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("A", Variable.from("A", "A", true, false, 0.0, 1.0));
        vars.put("B", Variable.from("B", "B", true, false, 0.0, 1.0));
        vars.put("C", Variable.from("C", "C", true, false, 0.0, 1.0));
        return buildGraph(vars, List.of("A->B", "B->C"), Map.of());
    }

    /** Single isolated variable with custom intercept/noise. */
    private static CompiledCausalGraph singleVariableGraph(double intercept, double noise) {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars.put("V", Variable.from("V", "V", true, false, intercept, noise));
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
