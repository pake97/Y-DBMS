package com.example.causal.runtime.compile;

import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.index.BitSet64;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Insertion-only, copy-on-write patch of a {@link CompiledCausalGraph}.
 *
 * <p>Applies added variables, added edges, and variable / edge-weight updates
 * without a full recompile. Only the closure rows whose sets can change are
 * recomputed; every other row is shared with the old snapshot by reference.
 * Removals, backward edges and edges out of new nodes fall back to a full
 * recompile ({@code null} result); CODE-PLAN.md WP3 removes those fallbacks.
 */
public final class SnapshotPatcher {

    private static final Logger LOG = Logger.getLogger(SnapshotPatcher.class.getName());

    private SnapshotPatcher() {}

    /** Returns the patched snapshot, or null when the batch must be applied by a full recompile. */
    public static CompiledCausalGraph apply(CompiledCausalGraph old, ChangeBatch batch) {
        if (!old.hasClosures()) {
            return null; // layer-pruned snapshot: nothing to patch incrementally, recompile
        }
        if (!batch.removedVariables().isEmpty() || !batch.removedEdges().isEmpty()) {
            return null; // non-local changes, fallback to recompile
        }
        if (batch.isEmpty()) {
            return old;
        }
        try {
            return patch(old, batch);
        } catch (PatchNotApplicable e) {
            LOG.fine(() -> "patch declined: " + e.getMessage());
            return null; // Topo or accounting error, fallback to recompile
        }
    }

    private static CompiledCausalGraph patch(CompiledCausalGraph old, ChangeBatch batch) {
        List<Variable> actualAddedVariables = batch.addedVariables().stream()
                .filter(v -> old.indexOf(v.id()) < 0)
                // Note: (a, b) -> b means last-wins. This is arbitrary (createdNodes order isn't specified),
                // but harmless since the database-level uniqueness constraint blocks parallel nodes anyway.
                .collect(Collectors.toMap(Variable::id, v -> v, (a, b) -> b, LinkedHashMap::new))
                .values().stream().toList();

        int oldN = old.size();
        int newN = oldN + actualAddedVariables.size();

        // ── Dense indices: old nodes keep theirs, added ones get oldN..newN-1 in batch order.
        Map<String, Integer> nameToIndex = new LinkedHashMap<>(old.indexMap());
        for (int i = oldN; i < newN; i++) nameToIndex.put(actualAddedVariables.get(i - oldN).id(), i);

        // ── Edge validation. Topo validation rules:
        // 1. If u and v are old, and u >= v (topological position) -> invalid backward edge (fallback)
        // 2. If u is new and v is old -> invalid backward edge (fallback)
        // 3. If u and v are new -> creation order isn't guaranteed topological (fallback)
        // 4. If u is old and v is new -> perfectly valid, v will be appended after u
        // TODO(future): The proper fix is to topologically sort actualAddedVariables
        // (against the batch's own edges) before assigning indices. For now, we fallback.
        List<int[]> newEdges = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ChangeBatch.Edge e : batch.addedEdges()) {
            int u = old.indexOf(e.from());
            Integer vBoxed = nameToIndex.get(e.to());
            if (u < 0) throw new PatchNotApplicable("edge from new or unknown variable " + e.from());
            if (vBoxed == null) throw new PatchNotApplicable("edge to unknown variable " + e.to());
            int v = vBoxed;
            if (v < oldN && old.topoPosition(u) >= old.topoPosition(v)) {
                throw new PatchNotApplicable("backward edge " + e.from() + " -> " + e.to());
            }
            if (old.forwardMap().getOrDefault(e.from(), Set.of()).contains(e.to())) continue; // already present
            if (seen.add(((long) u << 32) | v)) newEdges.add(new int[]{u, v});
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
        touchedFwd.forEach((k, set) -> forward.put(k, Collections.unmodifiableSet(set)));
        touchedRev.forEach((k, set) -> reverse.put(k, Collections.unmodifiableSet(set)));

        int[] fwdOffset = new int[newN + 1];
        int[] fwdTarget = csr(old, newN, addedChildren, fwdOffset, true);
        int[] revOffset = new int[newN + 1];
        int[] revTarget = csr(old, newN, addedParents, revOffset, false);

        // ── Order arrays: old topological order, then new nodes in batch order
        // (valid because edges out of new nodes and backward edges fall back above).
        int[] topoOrderIdx = Arrays.copyOf(old.topoOrderArray(), newN);
        for (int i = oldN; i < newN; i++) topoOrderIdx[i] = i;
        int[] topoPos = new int[newN];
        for (int k = 0; k < newN; k++) topoPos[topoOrderIdx[k]] = k;
        List<String> topoOrder = new ArrayList<>(old.topoOrder());
        for (int i = oldN; i < newN; i++) topoOrder.add(indexToVar[i].id());
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
            for (int w = 0; w < oldLongsPerNode; w++) needsDescendantUpdate[w] |= oldAnc[u][w];
        }

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

    /** CSR for one direction: each node's old neighbours (old order), then its added ones. */
    private static int[] csr(CompiledCausalGraph old, int newN, List<List<Integer>> added,
                             int[] offset, boolean forward) {
        int oldN = old.size();
        int[][] oldRows = new int[newN][];
        int m = 0;
        for (int i = 0; i < newN; i++) {
            oldRows[i] = i < oldN ? (forward ? old.childrenOf(i) : old.parentsOf(i)) : new int[0];
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

    private static final class PatchNotApplicable extends RuntimeException {
        PatchNotApplicable(String message) {
            super(message);
        }
    }
}
