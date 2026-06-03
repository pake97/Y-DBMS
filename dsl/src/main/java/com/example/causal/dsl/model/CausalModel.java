package com.example.causal.dsl.model;

import java.util.*;

/**
 * In-memory causal DAG. Stores variables and edges; provides structural algorithms
 * (ancestors, descendants, mediators, confounders, colliders, backdoor variables).
 *
 * All algorithms operate on variable names (strings) so the model is self-contained
 * and requires no Neo4j connection.
 */
public class CausalModel {

    private final String name;
    private final Map<String, CausalVariable> variables = new LinkedHashMap<>();
    private final Map<String, Set<String>> parents  = new HashMap<>();
    private final Map<String, Set<String>> children = new HashMap<>();
    /** Variables fixed by intervention: var -> value of do(var = value). Empty for a base model. */
    private final Map<String, String> fixedValues = new LinkedHashMap<>();

    // ─── Linear-Gaussian SEM parameters (all optional; absent ⇒ structural-only graph) ──
    /** Edge coefficients β, keyed child -> (parent -> β) to match parents(V) iteration. */
    private final Map<String, Map<String, Double>> weights = new HashMap<>();
    /** Per-variable intercept μ₀ (structural-equation constant). Default 0.0. */
    private final Map<String, Double> intercepts = new HashMap<>();
    /** Per-variable noise variance σ². Default 1.0. */
    private final Map<String, Double> noiseVars = new HashMap<>();

    public CausalModel(String name) {
        this.name = name;
    }

    public String name() { return name; }

    public void addVariable(CausalVariable v) {
        variables.put(v.name(), v);
        parents.putIfAbsent(v.name(), new LinkedHashSet<>());
        children.putIfAbsent(v.name(), new LinkedHashSet<>());
    }

    public void addEdge(String from, String to) {
        children.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        parents.computeIfAbsent(to,   k -> new LinkedHashSet<>()).add(from);
        // Auto-register unknown variable names (edges in a DAG-only block)
        variables.computeIfAbsent(from, k -> new CausalVariable(k, null, null, null, null));
        variables.computeIfAbsent(to,   k -> new CausalVariable(k, null, null, null, null));
        children.putIfAbsent(to,   new LinkedHashSet<>());
        parents.putIfAbsent(from,  new LinkedHashSet<>());
    }

    public Map<String, CausalVariable> variables() { return Collections.unmodifiableMap(variables); }

    public Optional<CausalVariable> variable(String name) {
        return Optional.ofNullable(variables.get(name));
    }

    // ─── Linear-Gaussian SEM parameters ─────────────────────────────────────────

    /** Declare the structural coefficient β for edge {@code from -> to}. */
    public void setWeight(String from, String to, double beta) {
        weights.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(from, beta);
    }

    /** Declare the intercept (structural-equation constant) for a variable. */
    public void setIntercept(String var, double mean) {
        intercepts.put(var, mean);
    }

    /** Declare the noise variance σ² for a variable. */
    public void setNoise(String var, double variance) {
        noiseVars.put(var, variance);
    }

    /** Coefficient β for edge {@code from -> to}; default 1.0 when not declared. */
    public double weight(String from, String to) {
        return weights.getOrDefault(to, Map.of()).getOrDefault(from, 1.0);
    }

    /** Intercept for a variable; default 0.0 when not declared. */
    public double intercept(String var) {
        return intercepts.getOrDefault(var, 0.0);
    }

    /** Noise variance σ² for a variable; default 1.0 when not declared. */
    public double noiseVar(String var) {
        return noiseVars.getOrDefault(var, 1.0);
    }

    /** True if any SEM parameter (weight, intercept, or noise) was explicitly declared. */
    public boolean isParameterized() {
        return !weights.isEmpty() || !intercepts.isEmpty() || !noiseVars.isEmpty();
    }

    /**
     * Topological order (parents before children) via Kahn's algorithm. Used by the
     * linear-Gaussian engine to propagate means/variances along the DAG.
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String v : variables.keySet()) indegree.put(v, parents.getOrDefault(v, Set.of()).size());
        Deque<String> ready = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String v = ready.poll();
            order.add(v);
            for (String child : children.getOrDefault(v, Set.of())) {
                int d = indegree.merge(child, -1, Integer::sum);
                if (d == 0) ready.add(child);
            }
        }
        return order;
    }

    // ─── Intervention (the do-operator) ─────────────────────────────────────────

    /** Variables fixed by intervention in this graph: {@code var -> value}. Empty for a base model. */
    public Map<String, String> fixedValues() {
        return Collections.unmodifiableMap(fixedValues);
    }

    /** True if this graph is the result of one or more interventions. */
    public boolean isIntervened() {
        return !fixedValues.isEmpty();
    }

    /**
     * Deterministic, ID-safe name for the world produced by {@code do(var = value)},
     * e.g. {@code UserRetentionModel_do_Treatment_1}. ID-safe so it round-trips
     * through {@code USE MODEL <name>}.
     */
    public static String derivedName(String base, String var, String value) {
        return base + "_do_" + var + "_" + value.replaceAll("[^A-Za-z0-9]", "");
    }

    /** Deep copy of this graph under a new name, preserving variables, edges and fixed values. */
    public CausalModel copy(String newName) {
        CausalModel c = new CausalModel(newName);
        for (CausalVariable v : variables.values()) c.addVariable(v);
        for (Map.Entry<String, Set<String>> e : children.entrySet())
            for (String to : e.getValue()) c.addEdge(e.getKey(), to);
        c.fixedValues.putAll(this.fixedValues);
        // Deep-copy SEM parameters (inner maps are mutable) so the derived world is independent.
        this.weights.forEach((to, byParent) -> c.weights.put(to, new LinkedHashMap<>(byParent)));
        c.intercepts.putAll(this.intercepts);
        c.noiseVars.putAll(this.noiseVars);
        return c;
    }

    /**
     * Apply the do-operator {@code do(var = value)}: return a new graph (named
     * {@code newName}) in which every edge <em>into</em> {@code var} is removed
     * and {@code var} is recorded as fixed to {@code value}. The original graph
     * is left untouched.
     *
     * <p>Surgery cuts only incoming edges, so {@code var}'s outgoing edges — and
     * therefore every directed path <em>from</em> {@code var} — are preserved.
     */
    public CausalModel intervene(String var, String value, String newName) {
        CausalModel c = copy(newName);
        for (String parent : c.parents.getOrDefault(var, Set.of())) {
            Set<String> parentChildren = c.children.get(parent);
            if (parentChildren != null) parentChildren.remove(var);
        }
        c.parents.put(var, new LinkedHashSet<>());
        c.fixedValues.put(var, value);
        return c;
    }

    // ─── Structural primitives ────────────────────────────────────────────────

    public Set<String> parents(String var) {
        return Set.copyOf(parents.getOrDefault(var, Set.of()));
    }

    public Set<String> children(String var) {
        return Set.copyOf(children.getOrDefault(var, Set.of()));
    }

    public Set<String> ancestors(String var) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(parents.getOrDefault(var, Set.of()));
        while (!queue.isEmpty()) {
            String v = queue.poll();
            if (result.add(v)) queue.addAll(parents.getOrDefault(v, Set.of()));
        }
        return result;
    }

    public Set<String> descendants(String var) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(var, Set.of()));
        while (!queue.isEmpty()) {
            String v = queue.poll();
            if (result.add(v)) queue.addAll(children.getOrDefault(v, Set.of()));
        }
        return result;
    }

    /**
     * Variables on directed paths from {@code from} to {@code to}, excluding endpoints.
     * Uses DFS with backtracking to find all paths.
     */
    public Set<String> mediators(String from, String to) {
        Set<String> result = new LinkedHashSet<>();
        findNodesOnDirectedPaths(from, to, new LinkedHashSet<>(), result);
        return result;
    }

    private boolean findNodesOnDirectedPaths(String cur, String target,
                                              Set<String> visited, Set<String> onPath) {
        if (cur.equals(target)) return true;
        if (!visited.add(cur)) return false;
        boolean reachesTarget = false;
        for (String child : children.getOrDefault(cur, Set.of())) {
            if (findNodesOnDirectedPaths(child, target, visited, onPath)) {
                if (!child.equals(target)) onPath.add(child);
                reachesTarget = true;
            }
        }
        visited.remove(cur);
        return reachesTarget;
    }

    /**
     * Common ancestors of {@code x} and {@code y} — variables that causally
     * precede both, excluding x and y themselves.
     */
    public Set<String> confounders(String x, String y) {
        Set<String> ax = ancestors(x);
        Set<String> ay = ancestors(y);
        ax.retainAll(ay);
        return ax;
    }

    /**
     * Nodes Z where effects of x and y "collide": Z has a parent reachable from x
     * and a parent reachable from y, but Z is not itself a cause of x or y.
     *
     * This is an approximation for v1: finds nodes downstream of both x and y
     * that are not ancestors of either.
     */
    public Set<String> colliders(String x, String y) {
        Set<String> reachableFromX = new HashSet<>(descendants(x)); reachableFromX.add(x);
        Set<String> reachableFromY = new HashSet<>(descendants(y)); reachableFromY.add(y);
        Set<String> ancX = ancestors(x);
        Set<String> ancY = ancestors(y);

        Set<String> result = new LinkedHashSet<>();
        for (String z : variables.keySet()) {
            if (z.equals(x) || z.equals(y)) continue;
            if (ancX.contains(z) || ancY.contains(z)) continue;
            Set<String> pz = parents.getOrDefault(z, Set.of());
            boolean xSide = pz.stream().anyMatch(reachableFromX::contains);
            boolean ySide = pz.stream().anyMatch(reachableFromY::contains);
            if (xSide && ySide) result.add(z);
        }
        return result;
    }

    /**
     * Variables involved in backdoor paths from {@code treatment} to {@code outcome}.
     * A backdoor path enters treatment via an incoming edge (a parent of treatment),
     * then reaches outcome through the undirected graph while avoiding descendants
     * of treatment.
     */
    public Set<String> backdoorVars(String treatment, String outcome) {
        Set<String> descTreatment = descendants(treatment);
        Set<String> treatmentParents = parents.getOrDefault(treatment, Set.of());
        if (treatmentParents.isEmpty()) return Set.of();

        // BFS from treatment's parents in the undirected graph, not crossing into
        // treatment's descendants (since those would be front-door paths, not backdoor).
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(treatmentParents);
        while (!queue.isEmpty()) {
            String v = queue.poll();
            if (v.equals(treatment) || descTreatment.contains(v)) continue;
            if (!visited.add(v)) continue;
            parents.getOrDefault(v, Set.of()).stream()
                .filter(n -> !n.equals(treatment) && !descTreatment.contains(n))
                .forEach(queue::add);
            children.getOrDefault(v, Set.of()).stream()
                .filter(n -> !n.equals(treatment) && !descTreatment.contains(n))
                .forEach(queue::add);
        }
        return visited;
    }

    /** Edge list in from->to order for display. */
    public List<String[]> edgeList() {
        List<String[]> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : children.entrySet()) {
            for (String to : e.getValue()) result.add(new String[]{e.getKey(), to});
        }
        return result;
    }
}
