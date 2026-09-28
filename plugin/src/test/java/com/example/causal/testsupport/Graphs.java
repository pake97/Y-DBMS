package com.example.causal.testsupport;

import com.example.causal.runtime.algorithms.TopologicalSorter;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds {@link CompiledCausalGraph}s in memory, without Neo4j, for unit tests. */
public final class Graphs {

    private static final Pattern SEED_ID   = Pattern.compile("\\{id:\\s*'([^']+)'");
    private static final Pattern SEED_EDGE = Pattern.compile("\\{from:\\s*'([^']+)',\\s*to:\\s*'([^']+)'");

    private Graphs() {}

    /** Graph from {@code "A->B"} strings; nodes are ordered by first appearance. */
    public static CompiledCausalGraph fromEdges(List<String> edges) {
        Set<String> nodes = new LinkedHashSet<>();
        List<String[]> pairs = new ArrayList<>();
        for (String edge : edges) {
            String[] parts = edge.split("->");
            nodes.add(parts[0]);
            nodes.add(parts[1]);
            pairs.add(parts);
        }
        return build(nodes, pairs);
    }

    /** Graph over ids {@code v0 .. v{n-1}} (inserted in that order) with {@code {from, to}} index pairs. */
    public static CompiledCausalGraph fromEdges(int n, int[][] edges) {
        Set<String> nodes = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) nodes.add("v" + i);
        List<String[]> pairs = new ArrayList<>();
        for (int[] e : edges) pairs.add(new String[]{"v" + e[0], "v" + e[1]});
        return build(nodes, pairs);
    }

    /**
     * Random DAG: shuffle {@code 0..n-1}, then add each forward pair of the
     * permutation with probability {@code density}. The topological order
     * therefore differs from the index order.
     */
    public static CompiledCausalGraph randomDag(int n, double density, long seed) {
        Random rnd = new Random(seed);
        List<Integer> perm = new ArrayList<>();
        for (int i = 0; i < n; i++) perm.add(i);
        Collections.shuffle(perm, rnd);
        List<int[]> edges = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (rnd.nextDouble() < density) edges.add(new int[]{perm.get(a), perm.get(b)});
            }
        }
        return fromEdges(n, edges.toArray(new int[0][]));
    }

    /** The COVID-19 seed DAG, parsed from {@code dev/seed-dag.cypher}'s UNWIND lists. */
    public static CompiledCausalGraph fromSeedDag() {
        String script = SeedDagSupport.readClasspathScript();
        Set<String> nodes = new LinkedHashSet<>();
        for (Matcher m = SEED_ID.matcher(script); m.find(); ) nodes.add(m.group(1));
        List<String[]> pairs = new ArrayList<>();
        for (Matcher m = SEED_EDGE.matcher(script); m.find(); ) pairs.add(new String[]{m.group(1), m.group(2)});
        return build(nodes, pairs);
    }

    private static CompiledCausalGraph build(Set<String> nodes, List<String[]> edges) {
        Map<String, Set<String>> forward = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        Map<String, Variable> variableById = new LinkedHashMap<>();
        for (String id : nodes) {
            forward.put(id, new LinkedHashSet<>());
            reverse.put(id, new LinkedHashSet<>());
            variableById.put(id, Variable.from(id, id, true, false, 0.0, 1.0));
        }
        for (String[] e : edges) {
            forward.get(e[0]).add(e[1]);
            reverse.get(e[1]).add(e[0]);
        }
        List<String> topoOrder = TopologicalSorter.sort(nodes, forward);
        try {
            Constructor<CompiledCausalGraph> constructor = CompiledCausalGraph.class.getDeclaredConstructor(
                    Map.class, Map.class, Map.class, List.class, Map.class, Instant.class);
            constructor.setAccessible(true);
            return constructor.newInstance(variableById, forward, reverse, topoOrder, Map.of(), Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to build test compiled graph", e);
        }
    }
}
