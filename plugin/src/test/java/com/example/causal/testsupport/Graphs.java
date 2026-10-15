package com.example.causal.testsupport;

import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.model.Variable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds {@link CompiledCausalGraph}s in memory, without Neo4j, for unit tests. */
public final class Graphs {

    private static final Pattern SEED_ID   = Pattern.compile("\\{id:\\s*'([^']+)'");
    private static final Pattern SEED_EDGE = Pattern.compile("\\{from:\\s*'([^']+)',\\s*to:\\s*'([^']+)'");
    private static final Pattern SEED_LATENT = Pattern.compile("\\{id:\\s*'([^']+)'[^}]*?latent:\\s*true");

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

    /** Graph from {@code "A->B"} strings with the given ids latent (not eligible for adjustment). */
    public static CompiledCausalGraph fromEdgesWithLatent(List<String> edges, Set<String> latent) {
        Set<String> nodes = new LinkedHashSet<>();
        List<String[]> pairs = new ArrayList<>();
        for (String edge : edges) {
            String[] parts = edge.split("->");
            nodes.add(parts[0]);
            nodes.add(parts[1]);
            pairs.add(parts);
        }
        return build(nodes, pairs, latent);
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

    /**
     * The COVID-19 seed DAG with its latent flags (observed = !latent), as WP5a's covid
     * dataset and the dagitty reference use it.
     */
    public static CompiledCausalGraph fromSeedDagWithLatents() {
        String script = SeedDagSupport.readClasspathScript();
        Set<String> nodes = new LinkedHashSet<>();
        for (Matcher m = SEED_ID.matcher(script); m.find(); ) nodes.add(m.group(1));
        Set<String> latent = new LinkedHashSet<>();
        for (Matcher m = SEED_LATENT.matcher(script); m.find(); ) latent.add(m.group(1));
        List<String[]> pairs = new ArrayList<>();
        for (Matcher m = SEED_EDGE.matcher(script); m.find(); ) pairs.add(new String[]{m.group(1), m.group(2)});
        return build(nodes, pairs, latent);
    }

    /**
     * A bnlearn network from {@code src/test/resources/bnlearn/<name>/edges.csv} (header
     * {@code from,to}; copied from WP5a's {@code bench/datasets/bnlearn/<name>/edges.csv}).
     */
    public static CompiledCausalGraph fromBnlearn(String name) {
        try (var in = Graphs.class.getResourceAsStream("/bnlearn/" + name + "/edges.csv")) {
            if (in == null) throw new IllegalStateException("no test resource bnlearn/" + name + "/edges.csv");
            List<String> lines = new String(in.readAllBytes()).lines().toList();
            Set<String> nodes = new LinkedHashSet<>();
            List<String[]> pairs = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] e = line.split(",");
                nodes.add(e[0]);
                nodes.add(e[1]);
                pairs.add(e);
            }
            return build(nodes, pairs, Set.of());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static CompiledCausalGraph build(Set<String> nodes, List<String[]> edges) {
        return build(nodes, edges, Set.of());
    }

    private static CompiledCausalGraph build(Set<String> nodes, List<String[]> edges, Set<String> latent) {
        List<Variable> vars = new ArrayList<>();
        for (String id : nodes) {
            boolean l = latent.contains(id);
            vars.add(Variable.from(id, id, !l, l, 0.0, 1.0));
        }
        return GraphBuilder.build(vars, edges, Map.of());
    }
}
