package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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
 * union of the deletion cones (the rows they recompute) exceeds {@code causal.patch.threshold * |V|} the patch
 * declines ({@code null}): this is its only fallback besides a layer-pruned snapshot.
 * A removed variable loses its incident edges through the same deletion path; its
 * dense index is then freed (see {@link CompiledCausalGraph}: no compaction until the
 * next full compile). Variable updates (e.g. a {@code latent} flip) replace the
 * variable and leave the closures untouched.
 *
 * <p>A batch that would close a cycle, or names an unknown endpoint, breaks an
 * invariant {@code CausalGraphChangeListener.beforeCommit} guarantees: the patch
 * throws {@link PatchNotApplicable} instead of silently recompiling.
 */
public final class SnapshotPatcher {

    private static final Logger LOG = Logger.getLogger(SnapshotPatcher.class.getName());

    private SnapshotPatcher() {}

    // Counters behind PatchStats (process-wide, monotone).
    static final AtomicLong PATCHES = new AtomicLong();
    static final AtomicLong RELABELLED = new AtomicLong();
    static final AtomicLong MOVED = new AtomicLong();
    static final AtomicLong DELETIONS = new AtomicLong();
    static final AtomicLong LAST_CONE = new AtomicLong();
    static final AtomicLong FALLBACK_THRESHOLD = new AtomicLong();
    static final AtomicLong FALLBACK_INVARIANT = new AtomicLong();
    static final AtomicLong FALLBACK_NO_CLOSURES = new AtomicLong();

    /** The counters now. */
    public static PatchStats stats() {
        return new PatchStats(PATCHES.get(), RELABELLED.get(), MOVED.get(), DELETIONS.get(), LAST_CONE.get(),
                FALLBACK_THRESHOLD.get(), FALLBACK_INVARIANT.get(), FALLBACK_NO_CLOSURES.get());
    }

    /** {@code -Dcausal.patch.threshold}: fraction of |V| the union of the deletion cones may reach; default 0.5. */
    static double threshold() {
        return Double.parseDouble(System.getProperty("causal.patch.threshold", "0.5"));
    }

    /** Returns the patched snapshot, or null when the batch must be applied by a full recompile. */
    public static CompiledCausalGraph apply(CompiledCausalGraph old, ChangeBatch batch) {
        if (batch.isEmpty()) {
            return old;
        }
        if (!old.hasClosures()) {
            FALLBACK_NO_CLOSURES.incrementAndGet();
            return null; // layer-pruned snapshot (causal.closures.budget): no closures to patch
        }
        try {
            return patch(old, batch);
        } catch (PatchNotApplicable e) {
            FALLBACK_INVARIANT.incrementAndGet();
            throw e;  // a broken beforeCommit invariant, not a fallback: the listener logs it and invalidates
        }
    }

    /**
     * The graph surgery of {@code do(intervened)}: {@code base} without the incoming edges of
     * the intervened variables (unknown ids are ignored), as one deletion batch through
     * {@link #apply}, so every untouched closure row is shared with {@code base}. When the
     * patch declines (deletion cones over the threshold, or a layer-pruned base without
     * closures) the world is built from scratch with {@link GraphBuilder#build} and
     * {@code fallback} is true.
     */
    public static Surgery cutIncomingEdges(CompiledCausalGraph base, Set<String> intervened) {
        ChangeBatch.Builder b = ChangeBatch.builder();
        int removed = 0;
        for (String x : intervened) {
            for (String p : base.parentsOf(x)) {
                b.removedEdge(p, x);
                removed++;
            }
        }
        ChangeBatch batch = b.build();
        CompiledCausalGraph patched = apply(base, batch);
        if (patched != null) return new Surgery(patched, removed, false);

        List<Variable> vars = new ArrayList<>();
        Map<String, String> elementIds = new HashMap<>();
        List<String[]> edges = new ArrayList<>();
        Map<String, Map<String, Double>> weights = new HashMap<>(base.edgeWeightMap());
        intervened.forEach(weights::remove);
        for (int i = 0; i < base.size(); i++) {
            Variable v = base.variableAt(i);
            if (v == null) continue;   // freed index
            vars.add(v);
            if (base.elementIdOf(v.id()) != null) elementIds.put(v.id(), base.elementIdOf(v.id()));
            for (String c : base.childrenOf(v.id())) {
                if (!intervened.contains(c)) edges.add(new String[]{v.id(), c});
            }
        }
        return new Surgery(GraphBuilder.build(vars, edges, weights, elementIds), removed, true);
    }

    /** A derived world's snapshot, the number of edges cut, and whether it was built from scratch. */
    public record Surgery(CompiledCausalGraph graph, int removedEdges, boolean fallback) {}

    private static CompiledCausalGraph patch(CompiledCausalGraph old, ChangeBatch batch) {
        // ── Removed variables the snapshot holds (a variable created and deleted in one
        // transaction never was); their incident edges are removed with them.
        Set<String> removedIds = new LinkedHashSet<>();
        for (String id : batch.removedVariables()) if (old.indexOf(id) >= 0) removedIds.add(id);
        List<ChangeBatch.Edge> removedEdges = new ArrayList<>(batch.removedEdges());
        for (String id : removedIds) {
            int x = old.indexOf(id);
            for (int k = 0; k < old.childCount(x); k++) removedEdges.add(new ChangeBatch.Edge(id, old.idOf(old.child(x, k)), null));
            for (int k = 0; k < old.parentCount(x); k++) removedEdges.add(new ChangeBatch.Edge(old.idOf(old.parent(x, k)), id, null));
        }

        // ── Removed edges present in the old snapshot; one removed and re-added in the same
        // batch is a no-op for the structure. Cones are measured on the old closures.
        Set<Long> addedPairs = new HashSet<>();
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            if (removedIds.contains(e.from()) || removedIds.contains(e.to())) continue; // a re-added variable is new
            int u = old.indexOf(e.from()), v = old.indexOf(e.to());
            if (u >= 0 && v >= 0) addedPairs.add(pair(u, v));
        }
        // The threshold is measured on the union of the deletion cones: the anc rows of
        // ∪ coneDown(v) and the des rows of ∪ coneUp(u), i.e. the rows the deletions recompute.
        Set<Long> removed = new HashSet<>();
        long[] ancCone = null, desCone = null;
        for (ChangeBatch.Edge e : removedEdges) {
            int u = old.indexOf(e.from()), v = old.indexOf(e.to());
            if (u < 0 || v < 0 || !old.childrenOf(e.from()).contains(e.to())) continue; // inactive or never compiled
            if (addedPairs.contains(pair(u, v)) || !removed.add(pair(u, v))) continue;
            if (ancCone == null) {
                ancCone = new long[old.wordCount()];
                desCone = new long[old.wordCount()];
            }
            BitSet64.or(ancCone, old.descendantBits(v));
            BitSet64.set(ancCone, v);
            BitSet64.or(desCone, old.ancestorBits(u));
            BitSet64.set(desCone, u);
        }
        long deletionCones = ancCone == null ? 0 : BitSet64.cardinality(ancCone) + BitSet64.cardinality(desCone);
        if (deletionCones > threshold() * old.liveCount()) {
            FALLBACK_THRESHOLD.incrementAndGet();
            long cones = deletionCones;
            LOG.fine(() -> "patch declined: deletion cones " + cones + " > " + threshold() + " * |V| = " + old.liveCount());
            return null;
        }

        List<Variable> actualAddedVariables = batch.addedVariables().stream()
                .filter(v -> old.indexOf(v.id()) < 0 || removedIds.contains(v.id()))
                // Note: (a, b) -> b means last-wins. This is arbitrary (createdNodes order isn't specified),
                // but harmless since the database-level uniqueness constraint blocks parallel nodes anyway.
                .collect(Collectors.toMap(Variable::id, v -> v, (a, b) -> b, LinkedHashMap::new))
                .values().stream().toList();
        // Edges between two new variables must be forward in the order the indices are assigned in.
        actualAddedVariables = sortAgainstBatchEdges(actualAddedVariables, batch.addedEdges());

        int oldN = old.size();
        int newN = oldN + actualAddedVariables.size();

        // ── Dense indices: old nodes keep theirs, added ones get oldN..newN-1 in sorted order,
        // removed ones are freed (their slots stay, empty, until the next full compile).
        Map<String, Integer> nameToIndex = new LinkedHashMap<>(old.indexMap());
        nameToIndex.keySet().removeAll(removedIds);
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
            if (u < oldN && v < oldN && old.forwardMap().getOrDefault(e.from(), Set.of()).contains(e.to())
                    && !removed.contains(pair(u, v))) {
                continue; // already present
            }
            if (seen.add(pair(u, v))) newEdges.add(new int[]{u, v});
        }

        // ── Variables
        // An update replaces the variable (latent/observed flips, SEM parameters); the closures
        // do not depend on it, and PathAnalyzer reads the flags from each snapshot it is given.
        Map<String, Variable> newVarById = new LinkedHashMap<>(old.variableMap());
        newVarById.keySet().removeAll(removedIds);
        Variable[] indexToVar = Arrays.copyOf(old.variableArray(), newN);
        for (Variable v : batch.updatedVariables()) {
            int idx = old.indexOf(v.id());
            if (idx < 0 || removedIds.contains(v.id())) continue;
            newVarById.put(v.id(), v);
            indexToVar[idx] = v;
        }
        for (String id : removedIds) indexToVar[old.indexOf(id)] = null;
        for (int i = oldN; i < newN; i++) {
            indexToVar[i] = actualAddedVariables.get(i - oldN);
            newVarById.put(indexToVar[i].id(), indexToVar[i]);
        }
        int live = old.liveCount() - removedIds.size() + actualAddedVariables.size();
        if (newVarById.size() != live || nameToIndex.size() != live) {
            throw new PatchNotApplicable("Accounting slip: expected " + live + " variables but got " + newVarById.size());
        }

        // ── Weights
        Map<String, Map<String, Double>> oldWeights = old.edgeWeightMap();
        Map<String, Map<String, Double>> newWeights = new HashMap<>(oldWeights);
        boolean weightsChanged = false;
        // Removed edges first, so a re-added variable's edge keeps the weight the batch gives it.
        for (long r : removed) {
            String from = old.idOf((int) (r >>> 32)), to = old.idOf((int) r);
            Map<String, Double> inner = newWeights.get(to);
            if (inner == null || !inner.containsKey(from)) continue;
            inner = new HashMap<>(inner);
            inner.remove(from);
            if (inner.isEmpty()) newWeights.remove(to); else newWeights.put(to, Collections.unmodifiableMap(inner));
            weightsChanged = true;
        }
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
        // Removals first (on the old ids), then the removed variables' own entries go, then
        // additions: a variable removed and re-added in the batch starts with no edges.
        Map<String, Set<String>> touchedFwd = new HashMap<>(), touchedRev = new HashMap<>();
        for (long r : removed) {
            String from = old.idOf((int) (r >>> 32)), to = old.idOf((int) r);
            touchedFwd.computeIfAbsent(from, k -> new LinkedHashSet<>(forward.getOrDefault(k, Set.of()))).remove(to);
            touchedRev.computeIfAbsent(to, k -> new LinkedHashSet<>(reverse.getOrDefault(k, Set.of()))).remove(from);
        }
        touchedFwd.forEach((k, set) -> forward.put(k, Collections.unmodifiableSet(set)));
        touchedRev.forEach((k, set) -> reverse.put(k, Collections.unmodifiableSet(set)));
        forward.keySet().removeAll(removedIds);
        reverse.keySet().removeAll(removedIds);
        for (int i = oldN; i < newN; i++) {
            forward.put(indexToVar[i].id(), Set.of());
            reverse.put(indexToVar[i].id(), Set.of());
        }
        List<List<Integer>> addedChildren = new ArrayList<>(Collections.nCopies(newN, null));
        List<List<Integer>> addedParents = new ArrayList<>(Collections.nCopies(newN, null));
        touchedFwd.clear();
        touchedRev.clear();
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
        touchedFwd.forEach((k, set) -> forward.put(k, Collections.unmodifiableSet(set)));
        touchedRev.forEach((k, set) -> reverse.put(k, Collections.unmodifiableSet(set)));

        int[] fwdOffset = new int[newN + 1];
        int[] fwdTarget = csr(old, newN, addedChildren, removed, fwdOffset, true);
        int[] revOffset = new int[newN + 1];
        int[] revTarget = csr(old, newN, addedParents, removed, revOffset, false);

        // ── Order arrays: old topological order without the removed variables, then new
        // nodes in their sorted order; then every added edge against that order is repaired
        // by a relabel. topoOrderIdx lists live indices only; a freed index has topoPos -1.
        int[] topoOrderIdx;
        if (removedIds.isEmpty()) {
            topoOrderIdx = Arrays.copyOf(old.topoOrderArray(), live);
        } else {
            topoOrderIdx = new int[live];
            int k = 0;
            for (int i : old.topoOrderArray()) if (indexToVar[i] != null) topoOrderIdx[k++] = i;
        }
        for (int i = oldN; i < newN; i++) topoOrderIdx[live - (newN - i)] = i;
        int[] topoPos = new int[newN];
        Arrays.fill(topoPos, -1);
        for (int k = 0; k < live; k++) topoPos[topoOrderIdx[k]] = k;
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
        if (relabelled == 0 && removedIds.isEmpty()) {
            topoOrder = new ArrayList<>(old.topoOrder());
            for (int i = oldN; i < newN; i++) topoOrder.add(indexToVar[i].id());
        } else {
            topoOrder = new ArrayList<>(live);
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
        LAST_CONE.set(BitSet64.cardinality(needsAncestorUpdate) + BitSet64.cardinality(needsDescendantUpdate));
        PATCHES.incrementAndGet();

        // Ancestors (and layers) of flagged rows, in topological order, from parents.
        for (int k = 0; k < live; k++) {
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
        for (int k = live - 1; k >= 0; k--) {
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

        // Freed indices: empty rows (fresh, so no shared row is touched), layer 0, no element id.
        String[] elementIds = Arrays.copyOf(old.elementIdArray(), newN);
        for (String id : removedIds) {
            int x = old.indexOf(id);
            ancestorBits[x] = new long[longsPerNode];
            descendantBits[x] = new long[longsPerNode];
            topoLayer[x] = 0;
            elementIds[x] = null;
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
                elementIds,  // added variables: no element id yet
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
