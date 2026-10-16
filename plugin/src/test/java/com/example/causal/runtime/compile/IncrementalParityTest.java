package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.model.Variable;
import com.example.causal.testsupport.Graphs;
import com.example.causal.testsupport.SnapshotParity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Random edit streams against the patch: after every application the patched snapshot
 * equals {@link GraphBuilder#build} of the maintained edge list (closures, layers,
 * adjacency, latent flags, topoPos monotone along every edge). The only fallback allowed
 * is the deletion threshold. Edits: insert an acyclic edge, delete an edge, flip
 * {@code active} (off, or back on when acyclic), flip {@code latent}, add a variable with
 * one edge, remove a variable; applied one at a time and in batches of 10, where a batch
 * is the net difference between the model before and after its edits (what the listener
 * emits for one transaction).
 */
class IncrementalParityTest {

    private static final int EDITS = 1000;

    static Stream<Arguments> fixtures() {
        Map<String, Supplier<CompiledCausalGraph>> graphs = new LinkedHashMap<>();
        graphs.put("seed DAG", Graphs::fromSeedDagWithLatents);
        for (String ds : List.of("asia", "sachs", "alarm", "hepar2")) graphs.put(ds, () -> Graphs.fromBnlearn(ds));
        graphs.put("randomDag(63, 0.05)", () -> Graphs.randomDag(63, 0.05, 0));   // grows across a word boundary
        graphs.put("randomDag(1000, 0.004)", () -> Graphs.randomDag(1000, 0.004, 0));
        List<Arguments> out = new ArrayList<>();
        graphs.forEach((name, g) -> {
            out.add(Arguments.of(name, g, 1));
            out.add(Arguments.of(name, g, 10));
        });
        return out.stream();
    }

    @ParameterizedTest(name = "{0}, batch {2}")
    @MethodSource("fixtures")
    void patchEqualsRebuild(String name, Supplier<CompiledCausalGraph> fixture, int batchSize) {
        CompiledCausalGraph g = fixture.get();
        Model model = new Model(g);
        Random rnd = new Random(name.hashCode() * 31L + batchSize);
        PatchStats start = SnapshotPatcher.stats();
        List<Long> cones = new ArrayList<>();
        int edits = 0, applications = 0, fallbacks = 0;
        while (edits < EDITS) {
            Model before = model.copy();
            for (int k = 0; k < batchSize && edits < EDITS; k++) {
                if (model.randomEdit(rnd)) edits++;
            }
            ChangeBatch batch = before.diff(model);
            PatchStats pre = SnapshotPatcher.stats();
            CompiledCausalGraph next = SnapshotPatcher.apply(g, batch);
            PatchStats post = SnapshotPatcher.stats();
            String where = name + " batch " + batchSize + " application " + applications;
            assertEquals(0, post.fallbacksInvariant() - pre.fallbacksInvariant(), where);
            if (next == null) {
                assertEquals(1, post.fallbacksThreshold() - pre.fallbacksThreshold(), where + ": null is the threshold only");
                assertFalse(batch.removedEdges().isEmpty() && batch.removedVariables().isEmpty(), where);
                fallbacks++;
                g = model.build();   // the recompile the listener's invalidate leads to
            } else {
                assertEquals(pre.fallbacksThreshold(), post.fallbacksThreshold(), where);
                if (next != g) cones.add(post.lastConeSize());
                SnapshotParity.assertEquivalent(next, model.build(), where);
                g = next;
            }
            applications++;
        }
        PatchStats end = SnapshotPatcher.stats();
        Collections.sort(cones);
        System.out.printf("WP3REPORT %s | batch %d | edits %d | applications %d | relabelled %d | moved %d | "
                        + "deletions %d | cone median %d max %d | fallbacks threshold %d invariant %d%n",
                name, batchSize, edits, applications,
                end.insertionsRelabelled() - start.insertionsRelabelled(), end.nodesMoved() - start.nodesMoved(),
                end.deletionsRecomputed() - start.deletionsRecomputed(),
                cones.isEmpty() ? 0 : cones.get(cones.size() / 2), cones.isEmpty() ? 0 : cones.get(cones.size() - 1),
                fallbacks, end.fallbacksInvariant() - start.fallbacksInvariant());
    }

    /** The graph as the database holds it: variables, active and inactive edges. */
    private static final class Model {
        final LinkedHashMap<String, Variable> vars = new LinkedHashMap<>();
        final LinkedHashSet<List<String>> active = new LinkedHashSet<>();
        final LinkedHashSet<List<String>> inactive = new LinkedHashSet<>();
        int fresh;

        Model(CompiledCausalGraph g) {
            for (String id : g.topoOrder()) vars.put(id, g.findVariable(id).orElseThrow());
            for (String id : g.topoOrder()) for (String c : g.childrenOf(id)) active.add(List.of(id, c));
        }

        private Model() {}

        Model copy() {
            Model m = new Model();
            m.vars.putAll(vars);
            m.active.addAll(active);
            m.inactive.addAll(inactive);
            m.fresh = fresh;
            return m;
        }

        CompiledCausalGraph build() {
            List<String[]> edges = new ArrayList<>();
            for (List<String> e : active) edges.add(new String[]{e.get(0), e.get(1)});
            return GraphBuilder.build(vars.values(), edges, Map.of());
        }

        /** The listener's batch for a transaction that turns {@code this} into {@code after}. */
        ChangeBatch diff(Model after) {
            ChangeBatch.Builder b = ChangeBatch.builder();
            after.vars.forEach((id, v) -> {
                Variable old = vars.get(id);
                if (old == null) b.addedVariable(v);
                else if (!old.equals(v)) b.updatedVariable(v);
            });
            vars.keySet().forEach(id -> { if (!after.vars.containsKey(id)) b.removedVariable(id); });
            for (List<String> e : after.active) if (!active.contains(e)) b.addedEdge(e.get(0), e.get(1));
            for (List<String> e : active) if (!after.active.contains(e)) b.removedEdge(e.get(0), e.get(1));
            return b.build();
        }

        boolean randomEdit(Random rnd) {
            List<String> ids = new ArrayList<>(vars.keySet());
            switch (rnd.nextInt(6)) {
                case 0: {   // insert an acyclic edge between two variables with no CAUSES yet
                    for (int t = 0; t < 20; t++) {
                        String a = ids.get(rnd.nextInt(ids.size())), b = ids.get(rnd.nextInt(ids.size()));
                        if (a.equals(b) || hasAny(a, b) || reaches(b, a)) continue;
                        active.add(List.of(a, b));
                        return true;
                    }
                    return false;
                }
                case 1: {   // delete an edge
                    if (active.isEmpty()) return false;
                    active.remove(pick(active, rnd));
                    return true;
                }
                case 2: {   // active flip: off, or back on when that stays acyclic
                    if (!inactive.isEmpty() && rnd.nextBoolean()) {
                        List<String> e = pick(inactive, rnd);
                        if (reaches(e.get(1), e.get(0))) return false;
                        inactive.remove(e);
                        active.add(e);
                        return true;
                    }
                    if (active.isEmpty()) return false;
                    List<String> e = pick(active, rnd);
                    active.remove(e);
                    inactive.add(e);
                    return true;
                }
                case 3: {   // latent flip (observed = !latent)
                    Variable v = vars.get(ids.get(rnd.nextInt(ids.size())));
                    vars.put(v.id(), Variable.from(v.id(), v.name(), v.latent(), !v.latent(), v.intercept(), v.noiseVariance()));
                    return true;
                }
                case 4: {   // a new variable with one edge, in either direction
                    String x = "new" + fresh++, y = ids.get(rnd.nextInt(ids.size()));
                    vars.put(x, Variable.from(x, x, true, false, 0.0, 1.0));
                    active.add(rnd.nextBoolean() ? List.of(x, y) : List.of(y, x));
                    return true;
                }
                default: {  // remove a variable and every CAUSES it has
                    if (ids.size() <= 2) return false;
                    String x = ids.get(rnd.nextInt(ids.size()));
                    vars.remove(x);
                    active.removeIf(e -> e.contains(x));
                    inactive.removeIf(e -> e.contains(x));
                    return true;
                }
            }
        }

        private boolean hasAny(String a, String b) {
            return active.contains(List.of(a, b)) || active.contains(List.of(b, a))
                    || inactive.contains(List.of(a, b)) || inactive.contains(List.of(b, a));
        }

        /** Directed path {@code from ->* to} over the active edges. */
        private boolean reaches(String from, String to) {
            Map<String, List<String>> children = new HashMap<>();
            for (List<String> e : active) children.computeIfAbsent(e.get(0), k -> new ArrayList<>()).add(e.get(1));
            Deque<String> stack = new ArrayDeque<>(List.of(from));
            Set<String> seen = new HashSet<>();
            while (!stack.isEmpty()) {
                String x = stack.pop();
                if (x.equals(to)) return true;
                if (seen.add(x)) stack.addAll(children.getOrDefault(x, List.of()));
            }
            return false;
        }

        private static <T> T pick(Collection<T> c, Random rnd) {
            int i = rnd.nextInt(c.size());
            for (T t : c) if (i-- == 0) return t;
            throw new IllegalStateException();
        }
    }
}
