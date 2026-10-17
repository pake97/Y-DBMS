package com.example.causal.bench.maintenance;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;

import java.util.*;

/**
 * Random edit batches for RQ3, from a fixed seed. Every batch starts from a base snapshot and
 * carries what a full recompile of the edited graph needs. Edit types:
 * <ul>
 *   <li>{@code insert}: an edge {@code u -> v} absent from the graph with no path {@code v ~> u}
 *       (earlier edits of the batch included);</li>
 *   <li>{@code delete}: an existing edge;</li>
 *   <li>{@code active}: {@code active} flips, as the listener reports them: even batches turn
 *       edges off (removed edges, from the dataset's snapshot), odd batches turn the previous
 *       batch's edges back on (added edges, from the snapshot without them);</li>
 *   <li>{@code latent}: a variable's {@code latent} flag flipped (an updated variable);</li>
 *   <li>{@code addvar}: a new variable with one edge from an existing one;</li>
 *   <li>{@code remove}: an existing variable, with its incident edges.</li>
 * </ul>
 * The cone of an edge edit {@code u -> v} is {@code |an(u)| + |de(v)|} in the base snapshot; of a
 * removed variable {@code x}, {@code |an(x)| + |de(x)|}; of the other edits 0.
 */
public final class EditStreams {

    public static final List<String> TYPES = List.of("insert", "delete", "active", "latent", "addvar", "remove");

    /** One batch: the snapshot it applies to, the batch, the recompile inputs, and the summed cones. */
    public record Item(CompiledCausalGraph base, ChangeBatch batch, List<Variable> vars, List<String[]> edges,
                       long coneSum, long coneMax) {}

    private EditStreams() {}

    /** {@code count} batches of {@code size} edits of {@code type} on {@code el}, seed {@code seed}. */
    public static List<Item> generate(EdgeList el, CompiledCausalGraph g0, String type, int size, int count, long seed) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("unknown edit type " + type);
        Random rnd = new Random(seed);
        List<Variable> vars0 = new ArrayList<>();
        for (int i = 0; i < g0.size(); i++) vars0.add(g0.variableAt(i));
        List<String[]> edges0 = new ArrayList<>();
        for (int[] e : el.edges()) edges0.add(new String[]{el.ids().get(e[0]), el.ids().get(e[1])});
        List<Item> out = new ArrayList<>();
        Item previous = null;
        for (int b = 0; b < count; b++) {
            Item it = "active".equals(type) && b % 2 == 1
                    ? reactivate(previous)
                    : batch(g0, vars0, edges0, type, size, rnd);
            out.add(it);
            previous = it;
        }
        return out;
    }

    private static Item batch(CompiledCausalGraph g, List<Variable> vars0, List<String[]> edges0, String type,
                              int size, Random rnd) {
        Map<String, Variable> vars = new LinkedHashMap<>();
        vars0.forEach(v -> vars.put(v.id(), v));
        LinkedHashMap<String, String[]> edges = new LinkedHashMap<>();
        edges0.forEach(e -> edges.put(key(e[0], e[1]), e));
        Map<String, Set<String>> out = new HashMap<>();
        edges0.forEach(e -> out.computeIfAbsent(e[0], x -> new HashSet<>()).add(e[1]));
        List<String> ids = new ArrayList<>(vars.keySet());
        ChangeBatch.Builder batch = ChangeBatch.builder();
        long coneSum = 0, coneMax = 0;
        int attempts = 0;
        for (int done = 0; done < size; ) {
            if (++attempts > 1000 * size) throw new IllegalStateException(type + ": cannot draw " + size + " edits");
            long cone = 0;
            switch (type) {
                case "insert" -> {
                    String u = ids.get(rnd.nextInt(ids.size())), v = ids.get(rnd.nextInt(ids.size()));
                    if (u.equals(v) || edges.containsKey(key(u, v)) || reaches(out, v, u)) continue;
                    edges.put(key(u, v), new String[]{u, v});
                    out.computeIfAbsent(u, x -> new HashSet<>()).add(v);
                    batch.addedEdge(u, v);
                    cone = cone(g, u, v);
                }
                case "delete", "active" -> {
                    if (edges.isEmpty()) throw new IllegalStateException(type + ": no edge left");
                    String[] e = edges0.get(rnd.nextInt(edges0.size()));
                    if (edges.remove(key(e[0], e[1])) == null) continue;
                    out.get(e[0]).remove(e[1]);
                    batch.removedEdge(e[0], e[1]);
                    cone = cone(g, e[0], e[1]);
                }
                case "latent" -> {
                    String x = ids.get(rnd.nextInt(ids.size()));
                    Variable v = vars.get(x);
                    if (v != vars0.get(g.indexOf(x))) continue;  // flip each variable at most once per batch
                    Variable f = Variable.from(v.id(), v.name(), v.latent(), !v.latent(), v.intercept(), v.noiseVariance());
                    vars.put(x, f);
                    batch.updatedVariable(f);
                }
                case "addvar" -> {
                    String x = "new" + vars.size(), u = ids.get(rnd.nextInt(ids.size()));
                    Variable v = Variable.from(x, x, true, false, Variable.DEFAULT_INTERCEPT, Variable.DEFAULT_NOISE_VARIANCE);
                    vars.put(x, v);
                    edges.put(key(u, x), new String[]{u, x});
                    batch.addedVariable(v).addedEdge(u, x);
                    cone = BitSet64.cardinality(g.ancestorBits(g.indexOf(u)));
                }
                case "remove" -> {
                    String x = ids.get(rnd.nextInt(ids.size()));
                    if (vars.remove(x) == null) continue;
                    edges.values().removeIf(e -> e[0].equals(x) || e[1].equals(x));
                    batch.removedVariable(x);
                    int i = g.indexOf(x);
                    cone = BitSet64.cardinality(g.ancestorBits(i)) + BitSet64.cardinality(g.descendantBits(i));
                }
                default -> throw new AssertionError(type);
            }
            coneSum += cone;
            coneMax = Math.max(coneMax, cone);
            done++;
        }
        return new Item(g, batch.build(), List.copyOf(vars.values()), List.copyOf(edges.values()), coneSum, coneMax);
    }

    /** The previous batch's removed edges, added back, on the snapshot without them. */
    private static Item reactivate(Item off) {
        CompiledCausalGraph base = GraphBuilder.build(off.vars(), off.edges(), Map.of());
        ChangeBatch.Builder b = ChangeBatch.builder();
        List<String[]> edges = new ArrayList<>(off.edges());
        long coneSum = 0, coneMax = 0;
        for (ChangeBatch.Edge e : off.batch().removedEdges()) {
            b.addedEdge(e.from(), e.to());
            edges.add(new String[]{e.from(), e.to()});
            long cone = cone(base, e.from(), e.to());
            coneSum += cone;
            coneMax = Math.max(coneMax, cone);
        }
        return new Item(base, b.build(), off.vars(), List.copyOf(edges), coneSum, coneMax);
    }

    private static long cone(CompiledCausalGraph g, String u, String v) {
        return BitSet64.cardinality(g.ancestorBits(g.indexOf(u))) + BitSet64.cardinality(g.descendantBits(g.indexOf(v)));
    }

    private static boolean reaches(Map<String, Set<String>> out, String from, String to) {
        Deque<String> stack = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>(stack);
        while (!stack.isEmpty()) {
            String x = stack.pop();
            if (x.equals(to)) return true;
            for (String c : out.getOrDefault(x, Set.of())) if (seen.add(c)) stack.push(c);
        }
        return false;
    }

    private static String key(String u, String v) {
        return u + "\u0000" + v;
    }
}
