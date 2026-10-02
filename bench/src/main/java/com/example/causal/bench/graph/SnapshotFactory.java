package com.example.causal.bench.graph;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.model.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CompiledCausalGraph from a WP5a edge list, through the plugin's GraphBuilder (no Neo4j). */
public final class SnapshotFactory {

    private SnapshotFactory() {}

    public static CompiledCausalGraph fromEdgeList(EdgeList el) {
        List<Variable> vars = new ArrayList<>(el.n());
        for (String id : el.ids()) {
            boolean latent = el.latent().contains(id);
            vars.add(Variable.from(id, id, !latent, latent, Variable.DEFAULT_INTERCEPT, Variable.DEFAULT_NOISE_VARIANCE));
        }
        List<String[]> edges = new ArrayList<>(el.m());
        for (int[] e : el.edges()) edges.add(new String[]{el.ids().get(e[0]), el.ids().get(e[1])});
        return GraphBuilder.build(vars, edges, Map.of());
    }
}
