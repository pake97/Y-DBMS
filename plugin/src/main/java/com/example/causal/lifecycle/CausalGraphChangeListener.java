package com.example.causal.lifecycle;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.GraphSnapshot;
import com.example.causal.runtime.DefaultCausalGraphRuntime;
import com.example.causal.runtime.ChangeBatch;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.algorithms.ReachabilityIndex;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.model.Variable;
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

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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

    // -------------------------------------------------------------------------
    // beforeCommit — observe + diff
    // -------------------------------------------------------------------------
    @Override
    public ChangeBatch beforeCommit(TransactionData data, Transaction tx, GraphDatabaseService db) {
        if (!touchesCausalSchema(data)) return ChangeBatch.EMPTY;

        Set<Node> createdNodes = new HashSet<>();
        data.createdNodes().forEach(createdNodes::add);
        Set<Node> deletedNodes = new HashSet<>();
        data.deletedNodes().forEach(deletedNodes::add);
        Set<Relationship> createdRels = new HashSet<>();
        data.createdRelationships().forEach(createdRels::add);
        Set<Relationship> deletedRels = new HashSet<>();
        data.deletedRelationships().forEach(deletedRels::add);

        ChangeBatch.Builder batch = ChangeBatch.builder();

        // Added variables: new :Variable nodes with an id property.
        for (Node n : data.createdNodes()) {
            if (n.hasLabel(VARIABLE) && n.hasProperty(PROP_ID)) {
                batch.addedVariable(variableFromNode(n));
            }
        }

        // Removed variables: for deleted nodes we can't call getProperty() on
        // the Node directly — Neo4j exposes the old values via removedNodeProperties.
        for (PropertyEntry<Node> rem : data.removedNodeProperties()) {
            if (PROP_ID.equals(rem.key()) && deletedNodes.contains(rem.entity())) {
                batch.removedVariable(String.valueOf(rem.previouslyCommittedValue()));
            }
        }

        // Added CAUSES edges (only those with active = true, matching GraphLoader).
        for (Relationship r : data.createdRelationships()) {
            if (r.isType(CAUSES) && isActive(r)) {
                String from = (String) r.getStartNode().getProperty(PROP_ID, null);
                String to   = (String) r.getEndNode().getProperty(PROP_ID, null);
                if (from != null && to != null) batch.addedEdge(from, to);
            }
        }

        // Removed CAUSES edges. Same caveat as deleted nodes — use the
        // listener's view of the relationship *before* deletion.
        for (Relationship r : data.deletedRelationships()) {
            if (r.isType(CAUSES)) {
                String from = getDeletedNodeID(data, deletedNodes, r.getStartNode());
                String to   = getDeletedNodeID(data, deletedNodes, r.getEndNode());
                if (from != null && to != null) {
                    batch.removedEdge(from, to);
                }
            }
        }

        // Property-change cases for relationships (e.g., flipping r.active)
        for (PropertyEntry<Relationship> entry : data.assignedRelationshipProperties()) {
            Relationship r = entry.entity();
            if (r.isType(CAUSES) && entry.key().equals(PROP_ACTIVE) && !deletedRels.contains(r) && !createdRels.contains(r)) {
                boolean oldVal = entry.previouslyCommittedValue() instanceof Boolean b ? b : true;
                boolean newVal = entry.value() instanceof Boolean b ? b : true;
                
                String from = (String) r.getStartNode().getProperty(PROP_ID, null);
                String to = (String) r.getEndNode().getProperty(PROP_ID, null);
                if (from != null && to != null) {
                    // Logically treat an activation as an added edge, and a deactivation as a removed edge
                    if (!oldVal && newVal) {
                        batch.addedEdge(from, to);
                    } else if (oldVal && !newVal) {
                        batch.removedEdge(from, to);
                    }
                }
            }
        }

        Set<Node> updatedNodes = new HashSet<>();
        for (PropertyEntry<Node> entry : data.assignedNodeProperties()) {
            Node n = entry.entity();
            if (n.hasLabel(VARIABLE) && !deletedNodes.contains(n) && !createdNodes.contains(n)) {
                if (!entry.key().equals(PROP_ID)) {
                    updatedNodes.add(n);
                }
            }
        }
        for (Node n : updatedNodes) {
            batch.updatedVariable(variableFromNode(n));
        }

        ChangeBatch builtBatch = batch.build();

        // Validation: Ensure topology invariants are respected before allowing the transaction to commit.
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        GraphSnapshot snapshot = runtime.currentSnapshot();
        if (snapshot instanceof CompiledCausalGraph cg && !builtBatch.addedEdges().isEmpty()) {
            Map<String, List<String>> addedAdj = new HashMap<>();
            for (ChangeBatch.Edge e : builtBatch.addedEdges()) {
                addedAdj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            }

            for (ChangeBatch.Edge e : builtBatch.addedEdges()) {
                boolean fromKnown = cg.topoIndex(e.from()) != -1 || builtBatch.addedVariables().stream().anyMatch(v -> v.id().equals(e.from()));
                boolean toKnown = cg.topoIndex(e.to()) != -1 || builtBatch.addedVariables().stream().anyMatch(v -> v.id().equals(e.to()));
                if (!fromKnown) {
                    throw new IllegalArgumentException("Edge references unknown variable: " + e.from());
                }
                if (!toKnown) {
                    throw new IllegalArgumentException("Edge references unknown variable: " + e.to());
                }

                // If a path exists from 'to' back to 'from' in the combined graph, we have a cycle.
                Set<String> visited = new HashSet<>();
                Deque<String> stack = new ArrayDeque<>();
                stack.push(e.to());
                boolean cycle = false;
                while (!stack.isEmpty()) {
                    String curr = stack.pop();
                    if (curr.equals(e.from())) {
                        cycle = true;
                        break;
                    }
                    if (visited.add(curr)) {
                        if (cg.topoIndex(curr) != -1) {
                            for (String child : cg.childrenOf(curr)) {
                                if (!visited.contains(child)) stack.push(child);
                            }
                        }
                        if (addedAdj.containsKey(curr)) {
                            for (String child : addedAdj.get(curr)) {
                                if (!visited.contains(child)) stack.push(child);
                            }
                        }
                    }
                }
                if (cycle) {
                    throw new IllegalArgumentException("Edge from " + e.from() + " to " + e.to() + " creates a cycle.");
                }
            }
        }

        return builtBatch;
    }

    // -------------------------------------------------------------------------
    // afterCommit — apply diff to the runtime
    // -------------------------------------------------------------------------

    @Override
    public void afterCommit(TransactionData data, ChangeBatch batch, GraphDatabaseService db) {
        if (batch == null || batch.isEmpty()) return;
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        if (runtime instanceof DefaultCausalGraphRuntime d) {
            try {
                if (d.applyChange(batch)) {
                    LOG.fine(() -> "Applied batch after transaction: " + summarise(batch));
                } else {
                    LOG.fine(() -> "Patch failed or unsupported; invalidating snapshot: " + summarise(batch));
                    runtime.invalidate();
                }
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "Failed to apply incremental update; invalidating snapshot", e);
                runtime.invalidate();
            }
        } else {
            runtime.invalidate();
        }
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
        for (LabelEntry l : data.assignedLabels())   if (l.label().equals(VARIABLE)) return true;
        for (LabelEntry l : data.removedLabels())    if (l.label().equals(VARIABLE)) return true;
        for (PropertyEntry<Relationship> entry : data.assignedRelationshipProperties()) {
            if (entry.entity().isType(CAUSES)) return true;
        }
        for (PropertyEntry<Node> entry : data.assignedNodeProperties()) {
            if (entry.entity().hasLabel(VARIABLE)) return true;
        }
        return false;
    }

    private static boolean isActive(Relationship r) {
        Object v = r.getProperty(PROP_ACTIVE, Boolean.TRUE);
        return v instanceof Boolean b ? b : true;
    }

    private static Variable variableFromNode(Node n) {
        String id = (String) n.getProperty(PROP_ID);
        String name = (String) n.getProperty("name", id);
        boolean observed = (boolean) n.getProperty("observed", Variable.DEFAULT_OBSERVED);
        boolean latent   = (boolean) n.getProperty("latent", Variable.DEFAULT_LATENT);
        double intercept = ((Number) n.getProperty("intercept", Variable.DEFAULT_INTERCEPT)).doubleValue();
        double noise     = ((Number) n.getProperty("noise", Variable.DEFAULT_NOISE_VARIANCE)).doubleValue();
        return Variable.from(id, name, observed, latent, intercept, noise);
    }

    private static String getDeletedNodeID(TransactionData data, Set<Node> deletedNodes, Node n) {
        if (deletedNodes.contains(n)) {
            for (PropertyEntry<Node> propEntry : data.removedNodeProperties()) {
                if (propEntry.entity().equals(n) && PROP_ID.equals(propEntry.key())) {
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
}
