package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Copy-on-write patch of a {@link CompiledCausalGraph}.
 *
 * <p>Applies added variables, added edges, and variable / edge-weight updates
 * without a full recompile. Only the closure rows whose sets can change are
 * recomputed; every other row is shared with the old snapshot by reference.
 * An inserted edge {@code u -> v} with {@code topoPos[u] > topoPos[v]} is handled
 * by a topological relabel: the descendants of {@code v} (and {@code v}) that
 * precede {@code u} move, in their relative order, to just after {@code u}.
 * New variables are first sorted against the batch's own edges (Kahn).
 * A removed (or deactivated) edge {@code u -> v} recomputes {@code anc} over
 * {@code coneDown(v) = des[v] | {v}} and {@code des} over {@code coneUp(u) = anc[u] | {u}}
 * (the old closures, so the cones are a superset of the rows that can change);
 * all deletion and insertion cones of a batch are recomputed in one sweep. When the
 * deletion cones, summed, exceed {@code causal.patch.threshold * |V|} the patch
 * declines ({@code null}): this is its only fallback besides a layer-pruned snapshot.
 * Variable removals still fall back.
 *
 * <p>A batch that would close a cycle, or names an unknown endpoint, breaks an
 * invariant {@code CausalGraphChangeListener.beforeCommit} guarantees: the patch
 * throws {@link PatchNotApplicable} instead of silently recompiling.
 */
public final class SnapshotPatcher {

    private static final Logger LOG = Logger.getLogger(SnapshotPatcher.class.getName());

    private SnapshotPatcher() {}

    /** Insertions that needed a topological relabel, and nodes moved by them (process-wide, monotone). */
    static final java.util.concurrent.atomic.AtomicLong RELABELLED = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong MOVED = new java.util.concurrent.atomic.AtomicLong();
    /** Removed edges whose cones were recomputed, and batches declined by the threshold. */
    static final java.util.concurrent.atomic.AtomicLong DELETIONS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong FALLBACK_THRESHOLD = new java.util.concurrent.atomic.AtomicLong();

    /** {@code -Dcausal.patch.threshold}: fraction of |V| the summed deletion cones may reach; default 0.5. */
    static double threshold() {
        return Double.parseDouble(System.getProperty("causal.patch.threshold", "0.5"));
    }

    /** Returns the patched snapshot, or null when the batch must be applied by a full recompile. */
    public static CompiledCausalGraph apply(CompiledCausalGraph old, ChangeBatch batch) {
        if (!old.hasClosures()) {
            return null; // layer-pruned snapshot: nothing to patch incrementally, recompile
        }
        if (!batch.removedVariables().isEmpty()) {
            return null; // variable removal: WP3 step 3
        }
        if (batch.isEmpty()) {
            return old;
        }
        return patch(old, batch);  // PatchNotApplicable propagates: a broken invariant, not a fallback
    }

    private static CompiledCausalGraph patch(CompiledCausalGraph old, ChangeBatch batch) {
        // ── Removed edges present in the old snapshot; one removed and re-added in the same
        // batch is a no-op for the structure. Cones are measured on the old closures.
        Set<Long> addedPairs = new HashSet<>();
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            int u = old.indexOf(e.from()), v = old.indexOf(e.to());
            if (u >= 0 && v >= 0) addedPairs.add(pair(u, v));
        }
        Set<Long> removed = new HashSet<>();
        long deletionCones = 0;
        for (ChangeBatch.Edge e : batch.removedEdges()) {
            int u = old.indexOf(e.from()), v = old.indexOf(e.to());
            if (u < 0 || v < 0 || !old.childrenOf(e.from()).contains(e.to())) continue; // inactive or never compiled
            if (addedPairs.contains(pair(u, v)) || !removed.add(pair(u, v))) continue;
            deletionCones += BitSet64.cardinality(old.descendantBits(v)) + 1
                    + BitSet64.cardinality(old.ancestorBits(u)) + 1;
        }
        if (deletionCones > threshold() * old.size()) {
            FALLBACK_THRESHOLD.incrementAndGet();
            long cones = deletionCones;
            LOG.fine(() -> "patch declined: deletion cones " + cones + " > " + threshold() + " * |V| = " + old.size());
            return null;
        }

        List<Variable> actualAddedVariables = batch.addedVariables().stream()
                .filter(v -> old.indexOf(v.id()) < 0)
                // Note: (a, b) -> b means last-wins. This is arbitrary (createdNodes order isn't specified),
                // but harmless since the database-level uniqueness constraint blocks parallel nodes anyway.
                .collect(Collectors.toMap(Variable::id, v -> v, (a, b) -> b, LinkedHashMap::new))
                .values().stream().toList();
        // Edges between two new variables must be forward in the order the indices are assigned in.
        actualAddedVariables = sortAgainstBatchEdges(actualAddedVariables, batch.addedEdges());

        int oldN = old.size();
        int newN = oldN + actualAddedVariables.size();

        // ── Dense indices: old nodes keep theirs, added ones get oldN..newN-1 in batch order.
        Map<String, Integer> nameToIndex = new LinkedHashMap<>(old.indexMap());
        for (int i = oldN; i < newN; i++) nameToIndex.put(actualAddedVariables.get(i - oldN).id(), i);

        // ── Edges. Any endpoint may be old or new; an edge against the topological order
        // is relabelled below, after the CSR is built.
        List<int[]> newEdges = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            Integer uBoxed = nameToIndex.get(e.from()), vBoxed = nameToIndex.get(e.to());
            if (uBoxed == null || vBoxed == null) {
                throw new PatchNotApplicable("edge " + e.from() + " -> " + e.to()
                        + " has an unknown endpoint; beforeCommit admits only edges between variables");
            }
            int u = uBoxed, v = vBoxed;
            if (old.forwardMap().getOrDefault(e.from(), Set.of()).contains(e.to())) continue; // already present
            if (seen.add(pair(u, v))) newEdges.add(new int[]{u, v});
        }

        // ── Variables
        Map<String, Variable> newVarById = new LinkedHashMap<>(old.variableMap());
        for (Variable v : batch.updatedVariables()) newVarById.put(v.id(), v);
        for (Variable v : actualAddedVariables) newVarById.put(v.id(), v);

        if (newVarById.size() != newN) {
            throw new PatchNotApplicable("Accounting slip: expected " + newN + " variables but got " + newVarById.size());
        }

        Variable[] indexToVar = Arrays.copyOf(old.variableArray(), newN);
        for (Variable v : batch.updatedVariables()) {
            int idx = old.indexOf(v.id());
            if (idx >= 0) indexToVar[idx] = v;
        }
        for (int i = oldN; i < newN; i++) indexToVar[i] = actualAddedVariables.get(i - oldN);

        // ── Weights
        Map<String, Map<String, Double>> oldWeights = old.edgeWeightMap();
        Map<String, Map<String, Double>> newWeights = new HashMap<>(oldWeights);
        boolean weightsChanged = false;
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            if (e.weight() != null) {
                // A null weight on an added edge means "no weight specified" (keep existing β if any).
                // We only mutate if a weight was explicitly provided.
                Map<String, Double> inner = new HashMap<>(newWeights.getOrDefault(e.to(), Map.of()));
                Double oldW = inner.put(e.from(), e.weight());
                if (oldW == null || !oldW.equals(e.weight())) {
                    newWeights.put(e.to(), Collections.unmodifiableMap(inner));
                    weightsChanged = true;
                }
            }
        }
        for (ChangeBatch.Edge e : batch.updatedEdges()) {
            Map<String, Double> inner = new HashMap<>(newWeights.getOrDefault(e.to(), Map.of()));
            if (e.weight() != null) {
                Double oldW = inner.put(e.from(), e.weight());
                if (oldW == null || !oldW.equals(e.weight())) {
                    newWeights.put(e.to(), Collections.unmodifiableMap(inner));
                    weightsChanged = true;
                }
            } else {
                // A null weight on an updated edge means the property was explicitly removed.
                // Remove it from the map so weight() naturally falls back to DEFAULT_WEIGHT.
                Double oldW = inner.remove(e.from());
                if (oldW != null) {
                    if (inner.isEmpty()) {
                        newWeights.remove(e.to());
                    } else {
                        newWeights.put(e.to(), Collections.unmodifiableMap(inner));
                    }
                    weightsChanged = true;
                }
            }
        }
        for (long r : removed) {
            String from = old.idOf((int) (r >>> 32)), to = old.idOf((int) r);
            Map<String, Double> inner = newWeights.get(to);
            if (inner == null || !inner.containsKey(from)) continue;
            inner = new HashMap<>(inner);
            inner.remove(from);
            if (inner.isEmpty()) newWeights.remove(to); else newWeights.put(to, Collections.unmodifiableMap(inner));
            weightsChanged = true;
        }
        Map<String, Map<String, Double>> edgeWeights =
                weightsChanged ? Collections.unmodifiableMap(newWeights) : oldWeights;

        // ── ALIASING INVARIANT ──
        // Rows are shared with the previous snapshot, never mutated. Any row this
        // constructor writes to is either freshly allocated (flagged rows below,
        // new nodes, or the widening branch) or replaced wholesale by reference
        // (Arrays.copyOf on adjacency). Never write into a row you did not allocate!
        //
        // The adjacency arrays below are safe because the added-edge loop later
        // replaces denseForwardAdj[u] with a copyOf rather than mutating it in place.
        //
        // (WP1.5: adjacency is now CSR and rebuilt in full below; the map-level
        // adjacency copies each touched set before adding to it.)

        // ── Adjacency: maps (string API) and CSR, old order first, new edges appended.
        Map<String, Set<String>> forward = new LinkedHashMap<>(old.forwardMap());
        Map<String, Set<String>> reverse = new LinkedHashMap<>(old.reverseMap());
        for (int i = oldN; i < newN; i++) {
            forward.put(indexToVar[i].id(), Set.of());
            reverse.put(indexToVar[i].id(), Set.of());
        }
        List<List<Integer>> addedChildren = new ArrayList<>(Collections.nCopies(newN, null));
        List<List<Integer>> addedParents = new ArrayList<>(Collections.nCopies(newN, null));
        Map<String, Set<String>> touchedFwd = new HashMap<>(), touchedRev = new HashMap<>();
        for (int[] e : newEdges) {
            int u = e[0], v = e[1];
            String from = indexToVar[u].id(), to = indexToVar[v].id();
            touchedFwd.computeIfAbsent(from, k -> new LinkedHashSet<>(forward.getOrDefault(k, Set.of()))).add(to);
            touchedRev.computeIfAbsent(to, k -> new LinkedHashSet<>(reverse.getOrDefault(k, Set.of()))).add(from);
            if (addedChildren.get(u) == null) addedChildren.set(u, new ArrayList<>());
            if (addedParents.get(v) == null) addedParents.set(v, new ArrayList<>());
            addedChildren.get(u).add(v);
            addedParents.get(v).add(u);
        }
        for (long r : removed) {
            String from = old.idOf((int) (r >>> 32)), to = old.idOf((int) r);
            touchedFwd.computeIfAbsent(from, k -> new LinkedHashSet<>(forward.getOrDefault(k, Set.of()))).remove(to);
            touchedRev.computeIfAbsent(to, k -> new LinkedHashSet<>(reverse.getOrDefault(k, Set.of()))).remove(from);
        }
        touchedFwd.forEach((k, set) -> forward.put(k, Collections.unmodifiableSet(set)));
        touchedRev.forEach((k, set) -> reverse.put(k, Collections.unmodifiableSet(set)));

        int[] fwdOffset = new int[newN + 1];
        int[] fwdTarget = csr(old, newN, addedChildren, removed, fwdOffset, true);
        int[] revOffset = new int[newN + 1];
        int[] revTarget = csr(old, newN, addedParents, removed, revOffset, false);

        // ── Order arrays: old topological order, then new nodes in their sorted order; then
        // every added edge against that order is repaired by a relabel.
        int[] topoOrderIdx = Arrays.copyOf(old.topoOrderArray(), newN);
        for (int i = oldN; i < newN; i++) topoOrderIdx[i] = i;
        int[] topoPos = new int[newN];
        for (int k = 0; k < newN; k++) topoPos[topoOrderIdx[k]] = k;
        int relabelled = 0;
        long[] scratch = new long[BitSet64.words(newN)];
        for (int[] e : newEdges) {
            if (topoPos[e[0]] < topoPos[e[1]]) continue;
            int moved = relabel(e[0], e[1], topoOrderIdx, topoPos, fwdOffset, fwdTarget, scratch, indexToVar);
            relabelled++;
            MOVED.addAndGet(moved);
        }
        RELABELLED.addAndGet(relabelled);
        List<String> topoOrder;
        if (relabelled == 0) {
            topoOrder = new ArrayList<>(old.topoOrder());
            for (int i = oldN; i < newN; i++) topoOrder.add(indexToVar[i].id());
        } else {
            topoOrder = new ArrayList<>(newN);
            for (int i : topoOrderIdx) topoOrder.add(indexToVar[i].id());
        }
        int[] topoLayer = Arrays.copyOf(old.layerArray(), newN);

        // ── Closures
        int longsPerNode = BitSet64.words(newN);
        int oldLongsPerNode = old.wordCount();
        boolean rowsAliased = (longsPerNode == oldLongsPerNode);
        long[][] oldAnc = old.ancestorRows(), oldDesc = old.descendantRows();
        long[][] ancestorBits = new long[newN][];
        long[][] descendantBits = new long[newN][];

        if (rowsAliased) {
            for (int i = 0; i < oldN; i++) {
                ancestorBits[i] = oldAnc[i];
                descendantBits[i] = oldDesc[i];
            }
        } else {
            for (int i = 0; i < oldN; i++) {
                ancestorBits[i] = Arrays.copyOf(oldAnc[i], longsPerNode);
                descendantBits[i] = Arrays.copyOf(oldDesc[i], longsPerNode);
            }
        }
        for (int i = oldN; i < newN; i++) {
            ancestorBits[i] = new long[longsPerNode];
            descendantBits[i] = new long[longsPerNode];
            topoLayer[i] = 0;
        }

        long[] needsAncestorUpdate = new long[longsPerNode];
        long[] needsDescendantUpdate = new long[longsPerNode];
        for (int i = oldN; i < newN; i++) {
            BitSet64.set(needsAncestorUpdate, i);
            BitSet64.set(needsDescendantUpdate, i);
        }

        for (int[] e : newEdges) {
            int u = e[0], v = e[1];
            // Seed the ancestor frontier from old.descendantBits[v] and the descendant frontier
            // from old.ancestorBits[u]. For any node z whose ancestor set changes, there is a path
            // from some added edge's head to z; taking the last added edge on that path (u'->v'),
            // the remainder v'->*z uses only committed edges, so z ∈ old.descendants(v') ∪ {v'},
            // and v' is a head, so z gets flagged. This correctly covers multi-edge batches
            // and chains through new nodes.
            BitSet64.set(needsAncestorUpdate, v);
            if (v < oldN) {
                for (int w = 0; w < oldLongsPerNode; w++) needsAncestorUpdate[w] |= oldDesc[v][w];
            }
            BitSet64.set(needsDescendantUpdate, u);
            if (u < oldN) {
                for (int w = 0; w < oldLongsPerNode; w++) needsDescendantUpdate[w] |= oldAnc[u][w];
            }
        }

        // Removed u -> v: anc can shrink on coneDown(v), des on coneUp(u) (old closures, a
        // superset of the rows that change). The sweeps below recompute deletion and
        // insertion cones together, from the final parents and children.
        for (long r : removed) {
            int u = (int) (r >>> 32), v = (int) r;
            BitSet64.set(needsAncestorUpdate, v);
            for (int w = 0; w < oldLongsPerNode; w++) needsAncestorUpdate[w] |= oldDesc[v][w];
            BitSet64.set(needsDescendantUpdate, u);
            for (int w = 0; w < oldLongsPerNode; w++) needsDescendantUpdate[w] |= oldAnc[u][w];
        }
        DELETIONS.addAndGet(removed.size());

        // Ancestors (and layers) of flagged rows, in topological order, from parents.
        for (int k = 0; k < newN; k++) {
            int i = topoOrderIdx[k];
            if (!BitSet64.get(needsAncestorUpdate, i)) continue;
            long[] row;
            if (rowsAliased && i < oldN) {
                row = new long[longsPerNode];
            } else {
                row = ancestorBits[i];
                Arrays.fill(row, 0L);
            }
            int maxParentLayer = -1;
            for (int e = revOffset[i]; e < revOffset[i + 1]; e++) {
                int p = revTarget[e];
                maxParentLayer = Math.max(maxParentLayer, topoLayer[p]);
                BitSet64.or(row, ancestorBits[p]);
                BitSet64.set(row, p);
            }
            ancestorBits[i] = row;
            topoLayer[i] = maxParentLayer + 1;
        }

        // Descendants of flagged rows, in reverse topological order, from children.
        for (int k = newN - 1; k >= 0; k--) {
            int i = topoOrderIdx[k];
            if (!BitSet64.get(needsDescendantUpdate, i)) continue;
            long[] row;
            if (rowsAliased && i < oldN) {
                row = new long[longsPerNode];
            } else {
                row = descendantBits[i];
                Arrays.fill(row, 0L);
            }
            for (int e = fwdOffset[i]; e < fwdOffset[i + 1]; e++) {
                int c = fwdTarget[e];
                BitSet64.or(row, descendantBits[c]);
                BitSet64.set(row, c);
            }
            descendantBits[i] = row;
        }

        return new CompiledCausalGraph(
                Collections.unmodifiableMap(newVarById),
                Collections.unmodifiableMap(forward),
                Collections.unmodifiableMap(reverse),
                Collections.unmodifiableList(topoOrder),
                edgeWeights,
                newN,
                Collections.unmodifiableMap(nameToIndex),
                indexToVar,
                fwdOffset, fwdTarget, revOffset, revTarget,
                topoOrderIdx, topoPos, topoLayer,
                longsPerNode,
                ancestorBits, descendantBits,
                Arrays.copyOf(old.elementIdArray(), newN),  // added variables: no element id yet
                Instant.now(),
                old.compiledAt());
    }

    /**
     * Repair the order for an inserted {@code u -> v} with {@code topoPos[u] > topoPos[v]}:
     * the nodes reachable from {@code v} that precede {@code u} (the part of coneDown(v)
     * that violates the order) move, in their relative order, to just after {@code u};
     * every other node keeps its relative order. Only positions in
     * {@code [topoPos[v], topoPos[u]]} change. Reachability is over the new CSR, so an
     * earlier batch edge is accounted for. Returns the number of nodes moved.
     *
     * <p>Valid because an edge {@code x -> y} with {@code x} moved has {@code y} moved or
     * already after {@code u}; one with only {@code y} moved has {@code x} before {@code u}.
     */
    private static int relabel(int u, int v, int[] order, int[] pos, int[] fwdOffset, int[] fwdTarget,
                               long[] inCone, Variable[] indexToVar) {
        int lo = pos[v], hi = pos[u];
        List<Integer> cone = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(v);
        BitSet64.set(inCone, v);
        while (!stack.isEmpty()) {
            int x = stack.pop();
            cone.add(x);
            for (int e = fwdOffset[x]; e < fwdOffset[x + 1]; e++) {
                int c = fwdTarget[e];
                if (c == u) {
                    throw new PatchNotApplicable("edge " + indexToVar[u].id() + " -> " + indexToVar[v].id()
                            + " closes a cycle; beforeCommit rejects cycles");
                }
                if (pos[c] < hi && !BitSet64.get(inCone, c)) {
                    BitSet64.set(inCone, c);
                    stack.push(c);
                }
            }
        }
        int[] seg = new int[hi - lo + 1];
        int k = 0;
        for (int p = lo; p <= hi; p++) if (!BitSet64.get(inCone, order[p])) seg[k++] = order[p];
        for (int p = lo; p <= hi; p++) if (BitSet64.get(inCone, order[p])) seg[k++] = order[p];
        System.arraycopy(seg, 0, order, lo, seg.length);
        for (int p = lo; p <= hi; p++) pos[order[p]] = p;
        for (int y : cone) BitSet64.clear(inCone, y);
        return cone.size();
    }

    /**
     * New variables in an order where every batch edge between two of them is forward
     * (Kahn over the sub-batch, ties in batch order).
     */
    private static List<Variable> sortAgainstBatchEdges(List<Variable> added, List<ChangeBatch.Edge> edges) {
        if (added.size() < 2) return added;
        Map<String, Variable> byId = new LinkedHashMap<>();
        added.forEach(v -> byId.put(v.id(), v));
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (ChangeBatch.Edge e : edges) {
            if (byId.containsKey(e.from()) && byId.containsKey(e.to())) {
                out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
                indegree.merge(e.to(), 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (String id : byId.keySet()) if (!indegree.containsKey(id)) ready.add(id);
        List<Variable> sorted = new ArrayList<>(added.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            sorted.add(byId.get(id));
            for (String c : out.getOrDefault(id, List.of())) {
                if (indegree.merge(c, -1, Integer::sum) == 0) ready.add(c);
            }
        }
        if (sorted.size() != added.size()) {
            throw new PatchNotApplicable("the batch's new variables form a cycle; beforeCommit rejects cycles");
        }
        return sorted;
    }

    private static long pair(int u, int v) {
        return ((long) u << 32) | (v & 0xffffffffL);
    }

    /**
     * CSR for one direction: each node's old neighbours (old order) minus the removed
     * edges, then its added ones. Rebuilt once per batch.
     */
    private static int[] csr(CompiledCausalGraph old, int newN, List<List<Integer>> added, Set<Long> removed,
                             int[] offset, boolean forward) {
        int oldN = old.size();
        int[][] oldRows = new int[newN][];
        int m = 0;
        for (int i = 0; i < newN; i++) {
            oldRows[i] = i < oldN ? (forward ? old.childrenOf(i) : old.parentsOf(i)) : new int[0];
            if (!removed.isEmpty() && i < oldN) {
                final int x = i;
                oldRows[i] = Arrays.stream(oldRows[i])
                        .filter(j -> !removed.contains(forward ? pair(x, j) : pair(j, x))).toArray();
            }
            offset[i] = m;
            m += oldRows[i].length + (added.get(i) == null ? 0 : added.get(i).size());
        }
        offset[newN] = m;
        int[] target = new int[m];
        for (int i = 0; i < newN; i++) {
            int e = offset[i];
            for (int j : oldRows[i]) target[e++] = j;
            if (added.get(i) != null) for (int j : added.get(i)) target[e++] = j;
        }
        return target;
    }

    /** A batch that breaks an invariant {@code beforeCommit} guarantees (cycle, unknown endpoint). */
    static final class PatchNotApplicable extends RuntimeException {
        PatchNotApplicable(String message) {
            super(message);
        }
    }
}
