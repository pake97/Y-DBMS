package com.example.causal.lifecycle;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.runtime.DefaultCausalGraphRuntime;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.LabelEntry;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListener;

import java.util.logging.Logger;
import java.util.*;

/**
 * Neo4j transaction listener that observes causal-schema mutations and keeps
 * the compiled snapshot in {@link RuntimeRegistry} in sync without the user
 * having to call {@code causal.compile()} / {@code causal.invalidate()}.
 *
 * <h2>Flow</h2>
 * <pre>
 *   Cypher write  ── Neo4j ──▶ beforeCommit(TransactionData)
 *                                    │
 *                                    ▼
 *                            ChangeBatch (diff)
 *                                    │   ◀── returned to Neo4j as the context
 *                                    ▼
 *   Neo4j commits ──────────▶ afterCommit(TransactionData, ChangeBatch)
 *                                    │
 *                                    ▼
 *                    Apply batch to the CausalGraphRuntime
 * </pre>
 *
 * <h2>Student A implementation surface (Tier 1)</h2>
 * <ol>
 *   <li><b>{@link #beforeCommit}</b> — build a {@link ChangeBatch} by walking
 *       {@link TransactionData}. Today's implementation covers created /
 *       deleted {@code :Variable} nodes and created / deleted {@code :CAUSES}
 *       relationships. <b>TODO:</b> property-change cases —
 *       e.g. {@code r.active} flipping true↔false should translate to an
 *       added or removed edge; {@code v.latent} flipping should trigger a
 *       property-only update.</li>
 *   <li><b>{@link #afterCommit}</b> — patch the runtime's compiled snapshot
 *       with the diff. Today this just calls
 *       {@link CausalGraphRuntime#invalidate} as a safe fallback so the next
 *       query triggers a full recompile. <b>TODO:</b> replace with incremental
 *       maintenance — extend {@code DefaultCausalGraphRuntime} with an
 *       {@code applyChange(ChangeBatch)} method that updates
 *       {@code CompiledCausalGraph}'s dense adjacency / topo layer /
 *       {@code ReachabilityIndex} bitsets in place. Fall back to
 *       {@code invalidate()} only for topology-breaking cases (e.g. an added
 *       edge that would create a cycle should be rejected in
 *       {@link #beforeCommit}; an edge removal that invalidates cached
 *       backdoor paths is fine to patch).</li>
 *   <li><b>{@link #beforeCommit} as a validator</b> — throwing from
 *       {@code beforeCommit} aborts the Neo4j transaction. Use this to reject
 *       writes that would break invariants: e.g. an edge whose endpoints are
 *       unknown variables, or an edge that would introduce a cycle.
 *       <b>TODO:</b> decide which invariants you want to enforce here
 *       vs. accept-and-repair in {@code afterCommit}.</li>
 * </ol>
 *
 * <p>The listener is scoped to a single database — see
 * {@link CausalGraphLifecycle} for the registration site.
 */
public final class CausalGraphChangeListener implements TransactionEventListener<ChangeBatch> {

    private static final Logger LOG = Logger.getLogger(CausalGraphChangeListener.class.getName());

    /** Label of nodes that participate in the causal graph. Must match {@code GraphLoader}. */
    public static final Label VARIABLE = Label.label("Variable");

    /** Relationship type for causal edges. Must match {@code GraphLoader}. */
    public static final RelationshipType CAUSES = RelationshipType.withName("CAUSES");

    /** Property on {@code :Variable} used as the canonical id. */
    public static final String PROP_ID = "id";

    /** Property on {@code :CAUSES} toggling whether the edge is loaded into the snapshot. */
    public static final String PROP_ACTIVE = "active";
    public static final String PROP_LATENT = "latent";
    public static final String PROP_OBSERVED = "observed";
    public static final String PROP_NAME = "name";

    // -------------------------------------------------------------------------
    // beforeCommit — observe + diff
    // -------------------------------------------------------------------------
    @Override
    public ChangeBatch beforeCommit(TransactionData data, Transaction tx, GraphDatabaseService db) {
        if (!touchesCausalSchema(data)) return ChangeBatch.EMPTY;

        ChangeBatch.Builder batch = ChangeBatch.builder();

        // Added variables: new :Variable nodes with an id property.
        for (Node n : data.createdNodes()) {
            if (n.hasLabel(VARIABLE) && n.hasProperty(PROP_ID)) {
                batch.addedVariable(String.valueOf(n.getProperty(PROP_ID)));
            }
        }

        // Removed variables: for deleted nodes we can't call getProperty() on
        // the Node directly — Neo4j exposes the old values via removedNodeProperties.
        for (PropertyEntry<Node> rem : data.removedNodeProperties()) {
            if (PROP_ID.equals(rem.key()) && isDeleted(data, rem.entity())) {
                batch.removedVariable(String.valueOf(rem.previouslyCommittedValue()));
            }
        }


        // Added CAUSES edges (only those with active = true, matching GraphLoader).
        for (Relationship r : data.createdRelationships()) {
            if (r.isType(CAUSES) && isActive(r)) {
                String from = nodeId(r.getStartNode());
                String to   = nodeId(r.getEndNode());
                if (from != null && to != null) batch.addedEdge(from, to);
            }
        }

        // Removed CAUSES edges. Same caveat as deleted nodes — use the
        // listener's view of the relationship *before* deletion.
        for (Relationship r : data.deletedRelationships()) {
            if (r.isType(CAUSES)) {
                String from = getDeletedNodeID(data, r.getStartNode());
                String to   = getDeletedNodeID(data, r.getEndNode());
                if (from != null && to != null) {
                    batch.removedEdge(from, to);
                }
             }
         }

        // Property flips on active CAUSES rels.
        for (PropertyEntry<Relationship> prop : data.assignedRelationshipProperties()) {
            if (!PROP_ACTIVE.equals(prop.key())) continue;
            Relationship r = prop.entity();
            if (!r.isType(CAUSES)) continue;
            boolean prev = asBoolean(prop.previouslyCommittedValue(), true);
            boolean next = asBoolean(prop.value(), true);
            if (prev == next) continue;

            String from = nodeId(r.getStartNode());
            String to = nodeId(r.getEndNode());
            if (from == null || to == null) continue;
            if (!prev && next) batch.addedEdge(from, to);
            if (prev && !next) batch.removedEdge(from, to);
        }

        // Tier 1 behavior: variable property updates still require a full
        // invalidate because ChangeBatch currently patches only topology.
        // TODO(tier 2): add property updates to ChangeBatch and evolve the snapshot incrementally, instead of full invalidation.
        for (PropertyEntry<Node> prop : data.assignedNodeProperties()) {
            Node n = prop.entity();
            if (!n.hasLabel(VARIABLE)) continue;
            String key = prop.key();
            if (PROP_LATENT.equals(key) || PROP_OBSERVED.equals(key) || PROP_NAME.equals(key) || PROP_ID.equals(key)) {
                batch.requireInvalidate();
                break;
            }
        }

        ChangeBatch built = batch.build();
        // validate the batch, throw if it would create a cycle in the resulting DAG
        validateBatchOrThrow(built);
        return built;
    }

    // -------------------------------------------------------------------------
    // afterCommit — apply diff to the runtime
    // -------------------------------------------------------------------------

    @Override
    public void afterCommit(TransactionData data, ChangeBatch batch, GraphDatabaseService db) {
        if (batch == null || batch.isEmpty()) return;

        // TODO(student, tier 1): replace this with an incremental update.
        //
        // Recommended approach:
        //   1. Add `void applyChange(ChangeBatch batch)` to DefaultCausalGraphRuntime
        //      (not to the public CausalGraphRuntime API — this is an internal hook).
        //   2. In that method:
        //        - For each added variable: allocate a new dense index; extend the
        //          int[][] adjacency, int[] topoOrder, long[][] ancestor/descendantBits.
        //        - For each removed variable: free the index; scrub incident edges.
        //        - For each added edge (u → v): append v to forwardAdj[u], u to
        //          reverseAdj[v]; update topoLayer[v] if needed; OR-in ancestorBits[u]
        //          into ancestorBits[v] and descendantBits[v] into descendantBits[u];
        //          cascade through descendants of v (or ancestors of u) to keep the
        //          closure consistent.
        //        - For each removed edge: harder — ancestor/descendant closures
        //          can shrink in non-local ways. Simplest correct patch is to
        //          invalidate and let the next query recompile.
        //   3. Cast RuntimeRegistry.getInstance() to DefaultCausalGraphRuntime and
        //      call applyChange(batch). Keep the invalidate() fallback for cases
        //      where the incremental update is not yet implemented.
        //
        // Safe default until step 1 lands: blow away the snapshot.
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        if (batch.requiresInvalidate()) {
            runtime.invalidate();
            LOG.fine(() -> "Invalidated snapshot after transaction (property update): " + summarise(batch));
            return;
        }
        if (runtime instanceof DefaultCausalGraphRuntime internal) {
            try {
                if (internal.applyChange(batch)) {
                    LOG.fine(() -> "Applied incremental snapshot patch: " + summarise(batch));
                    return;
                }
            } catch (RuntimeException ex) {
                LOG.warning("Incremental patch failed, falling back to invalidate: " + ex.getMessage());
            }
        }
        runtime.invalidate();
        LOG.fine(() -> "Invalidated snapshot after transaction (fallback): " + summarise(batch));
    }
    @Override
    public void afterRollback(TransactionData data, ChangeBatch batch, GraphDatabaseService db) {
        // Nothing to undo — beforeCommit did not mutate the runtime.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Quick reject: if the transaction touched nothing that can influence the
     * causal schema, skip the full scan in {@link #beforeCommit}.
     */
    private static boolean touchesCausalSchema(TransactionData data) {
        if (data.createdNodes().iterator().hasNext()) return true;
        if (data.deletedNodes().iterator().hasNext()) return true;
        if (data.createdRelationships().iterator().hasNext()) return true;
        if (data.deletedRelationships().iterator().hasNext()) return true;
        if (data.assignedRelationshipProperties().iterator().hasNext()) return true;
        if (data.assignedNodeProperties().iterator().hasNext()) return true;
        for (LabelEntry l : data.assignedLabels())   if (l.label().equals(VARIABLE)) return true;
        for (LabelEntry l : data.removedLabels())    if (l.label().equals(VARIABLE)) return true;
        return false;
    }

    private static boolean isActive(Relationship r) {
        Object v = r.getProperty(PROP_ACTIVE, Boolean.TRUE);
        return v instanceof Boolean b ? b : true;
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        return value instanceof Boolean b ? b : defaultValue;
    }

    private static boolean isDeleted(TransactionData data, Node n) {
        for (Node deleted : data.deletedNodes()) if (deleted.equals(n)) return true;
        return false;
    }

    private static String getDeletedNodeID(TransactionData data, Node n) {
        if (isDeleted(data, n)) {
            for (PropertyEntry<Node> propEntry : data.removedNodeProperties()) {
                if (propEntry.entity().getId() == n.getId() && PROP_ID.equals(propEntry.key())) {
                    Object val = propEntry.previouslyCommittedValue();
                    return val == null ? null : String.valueOf(val);
                }
            }
            return null;
        } else {
            // Node still exists, safe to read directly
            Object val = n.getProperty(PROP_ID, null);
            return val != null ? String.valueOf(val) : null;
        }
    }

    private static String summarise(ChangeBatch b) {
        return String.format(
            "+%d vars, -%d vars, +%d edges, -%d edges",
            b.addedVariables().size(),
            b.removedVariables().size(),
            b.addedEdges().size(),
            b.removedEdges().size()
        );
    }

    private static String nodeId(Node node) {
        Object id = node.getProperty(PROP_ID, null);
        return id == null ? null : String.valueOf(id);
    }

    private static void validateBatchOrThrow(ChangeBatch batch) {
        // validation against the staged graph + all in-transaction changes, not just the current snapshot.
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        if (!(runtime instanceof DefaultCausalGraphRuntime internal)) return;
        if (!(internal.currentSnapshot() instanceof CompiledCausalGraph snapshot)) return;

        Map<String, Set<String>> adjacency = mutableCopy(snapshot.forwardAdjView());
        Set<String> variables = new LinkedHashSet<>(snapshot.variableByIdView().keySet());

        for (String id : batch.addedVariables()) {
            variables.add(id);
            adjacency.putIfAbsent(id, new LinkedHashSet<>());
        }
        for (String id : batch.removedVariables()) {
            variables.remove(id);
            adjacency.remove(id);
        }
        for (Set<String> children : adjacency.values()) children.removeAll(batch.removedVariables());

        for (ChangeBatch.Edge e : batch.removedEdges()) {
            Set<String> children = adjacency.get(e.from());
            if (children != null) children.remove(e.to());
        }

        for (ChangeBatch.Edge e : batch.addedEdges()) {
            if (!variables.contains(e.from()) || !variables.contains(e.to())) {
                throw new IllegalStateException(
                        "reject write: edge endpoint missing in variable set: " + e.from() + " -> " + e.to());
            }
            adjacency.putIfAbsent(e.from(), new LinkedHashSet<>());
            adjacency.putIfAbsent(e.to(), new LinkedHashSet<>());
            if (createsCycle(adjacency, e.from(), e.to())) {
                throw new IllegalStateException(
                        "reject write: cycle would be introduced by edge " + e.from() + " -> " + e.to());
            }
            adjacency.get(e.from()).add(e.to());
        }
    }

    private static Map<String, Set<String>> mutableCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return copy;
    }

    private static boolean createsCycle(Map<String, Set<String>> adjacency, String from, String to) {
        if (from.equals(to)) return true;
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(to);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!visited.add(cur)) continue;
            if (cur.equals(from)) return true;
            for (String next : adjacency.getOrDefault(cur, Set.of())) stack.push(next);
        }
        return false;
    }
}
