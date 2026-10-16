package com.example.causal.procedures;

import com.example.causal.api.*;
import com.example.causal.dto.*;
import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.SnapshotPatcher;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Neo4j user-defined procedures for the causal graph runtime.
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li>Procedures are <strong>thin</strong>: they validate arguments, delegate to
 *       {@link CausalGraphRuntime}, and map the result to a DTO stream.</li>
 *   <li>No graph traversal logic lives here.</li>
 *   <li>Structural procedures accept and yield {@link Node} entities at the Cypher
 *       boundary; {@link ProcedureVariables} maps them to {@link VariableRef} for
 *       the runtime and re-hydrates nodes from the active transaction on output.</li>
 *   <li>{@link Transaction} is used for {@link GraphCompileOptions} on
 *       {@code compile()}, and for node lookup on all other procedures.</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Neo4j discovers these procedures automatically when the JAR is in {@code plugins/}.
 * No manual registration is required.
 */
@SuppressWarnings("unused") // Neo4j discovers and calls these via reflection
public class CausalProcedures {

    /** Injected by Neo4j — the active transaction for the current procedure call. */
    @Context
    public Transaction tx;

    // =========================================================================
    // causal.compile()
    // =========================================================================

    @Procedure(name = ProcedureNames.COMPILE, mode = Mode.READ)
    @Description(
        "Compile the causal graph from Neo4j (:Variable nodes, :CAUSES relationships) " +
        "into an in-memory snapshot. Must be called before any structural query procedure. " +
        "Safe to call again after graph mutations — replaces the previous snapshot atomically."
    )
    public Stream<SnapshotRecord> compile() {
        GraphSnapshot snap = RuntimeRegistry.getInstance()
                .compile(new GraphCompileOptions(tx));
        return Stream.of(toSnapshotRecord(snap));
    }

    // =========================================================================
    // causal.invalidate()
    // =========================================================================

    @Procedure(name = ProcedureNames.INVALIDATE, mode = Mode.READ)
    @Description(
        "Discard the current compiled snapshot. " +
        "The next call to causal.compile() will re-read the graph from Neo4j."
    )
    public Stream<SnapshotRecord> invalidate() {
        RuntimeRegistry.getInstance().invalidate();
        return Stream.of(new SnapshotRecord(0L, List.of(), "invalidated", SnapshotPatcher.stats()));
    }

    // =========================================================================
    // causal.parents(variables)
    // =========================================================================

    @Procedure(name = ProcedureNames.PARENTS, mode = Mode.READ)
    @Description("Return the direct parents (immediate causes) of each :Variable node.")
    public Stream<VariableNodeRecord> parents(
            @Name("variables") List<Node> variables
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toVariableNodes(runtime.parents(ProcedureVariables.resolveAll(runtime, variables)));
    }

    // =========================================================================
    // causal.children(variables)
    // =========================================================================

    @Procedure(name = ProcedureNames.CHILDREN, mode = Mode.READ)
    @Description("Return the direct children (immediate effects) of each :Variable node.")
    public Stream<VariableNodeRecord> children(
            @Name("variables") List<Node> variables
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toVariableNodes(runtime.children(ProcedureVariables.resolveAll(runtime, variables)));
    }

    // =========================================================================
    // causal.ancestors(variables)
    // =========================================================================

    @Procedure(name = ProcedureNames.ANCESTORS, mode = Mode.READ)
    @Description("Return all ancestors (transitive causes) of each :Variable node.")
    public Stream<VariableNodeRecord> ancestors(
            @Name("variables") List<Node> variables
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toVariableNodes(runtime.ancestors(ProcedureVariables.resolveAll(runtime, variables)));
    }

    // =========================================================================
    // causal.descendants(variables)
    // =========================================================================

    @Procedure(name = ProcedureNames.DESCENDANTS, mode = Mode.READ)
    @Description("Return all descendants (transitive effects) of each :Variable node.")
    public Stream<VariableNodeRecord> descendants(
            @Name("variables") List<Node> variables
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toVariableNodes(runtime.descendants(ProcedureVariables.resolveAll(runtime, variables)));
    }

    // =========================================================================
    // causal.dsep(x, y, z)
    // =========================================================================

    @Procedure(name = ProcedureNames.DSEP, mode = Mode.READ)
    @Description(
        "Test d-separation: are the variables in X independent of those in Y " +
        "conditional on Z? (X ⊥ Y | Z in the causal DAG)"
    )
    public Stream<DSeparationRecord> dsep(
            @Name("x") List<Node> x,
            @Name("y") List<Node> y,
            @Name("z") List<Node> z
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        DSeparationResult result = runtime.isDSeparated(
                ProcedureVariables.resolveAll(runtime, x),
                ProcedureVariables.resolveAll(runtime, y),
                ProcedureVariables.resolveAll(runtime, z));
        return Stream.of(new DSeparationRecord(result.separated(), result.explanation()));
    }

    // =========================================================================
    // causal.adjustmentCandidate(treatment, outcome)
    // =========================================================================

    @Procedure(name = ProcedureNames.ADJUSTMENT_SETS, mode = Mode.READ)
    @Description(
        "Adjustment sets for the total effect of treatment on outcome under the generalized " +
        "adjustment criterion. mode: 'canonical', 'minimal' or 'all-minimal' (default, up to limit)."
    )
    public Stream<AdjustmentSetsRecord> adjustmentSets(
            @Name("treatment") Node treatment,
            @Name("outcome")   Node outcome,
            @Name(value = "mode",  defaultValue = "all-minimal") String mode,
            @Name(value = "limit", defaultValue = "100") long limit
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef t = ProcedureVariables.requireResolved(runtime, treatment, "treatment");
        VariableRef o = ProcedureVariables.requireResolved(runtime, outcome, "outcome");
        AdjustmentSetsResult r = runtime.adjustmentSets(t, o, mode, (int) Math.min(limit, Integer.MAX_VALUE));
        List<List<Node>> sets = r.sets().stream().map(this::toNodes).collect(Collectors.toList());
        return Stream.of(new AdjustmentSetsRecord(r.identifiable(), toNodes(r.canonical()), toNodes(r.forbidden()),
                sets, r.mode(), r.explanation()));
    }

    private List<Node> toNodes(Set<VariableRef> refs) {
        return refs.stream()
                .sorted(Comparator.comparing(VariableRef::id))
                .map(ref -> ProcedureVariables.lookup(tx, snapshotGraph(), ref.id()))
                .collect(Collectors.toList());
    }

    /** Alias of {@code causal.adjustmentSets(t, o, 'canonical')} in the pre-WP2 record shape. */
    @Procedure(name = ProcedureNames.ADJUSTMENT_CANDIDATE, mode = Mode.READ)
    @Description(
        "Find candidate adjustment sets for identifying the causal effect of " +
        "treatment on outcome from observational data (alias of causal.adjustmentSets in mode canonical)."
    )
    public Stream<AdjustmentRecord> adjustmentCandidate(
            @Name("treatment") Node treatment,
            @Name("outcome")   Node outcome
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef t = ProcedureVariables.requireResolved(runtime, treatment, "treatment");
        VariableRef o = ProcedureVariables.requireResolved(runtime, outcome, "outcome");

        AdjustmentCandidateResult result = runtime.candidateAdjustmentSet(t, o);

        List<List<Node>> sets = result.candidateSets().stream()
                .map(s -> s.stream()
                        .sorted(Comparator.comparing(VariableRef::id))
                        .map(ref -> ProcedureVariables.lookup(tx, snapshotGraph(), ref.id()))
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());

        return Stream.of(new AdjustmentRecord(result.adjustmentSetExists(), sets, result.explanation()));
    }

    // =========================================================================
    // causal.mediators(from, to)
    // =========================================================================

    @Procedure(name = ProcedureNames.MEDIATORS, mode = Mode.READ)
    @Description(
        "Return variables on at least one directed path from from to to, " +
        "excluding the endpoints. Used by the DSL to answer MEDIATORS BETWEEN."
    )
    public Stream<VariableNodeRecord> mediators(
            @Name("from") Node from,
            @Name("to")   Node to
    ) {
        return pathQuery(from, to, CausalGraphRuntime::mediators);
    }

    // =========================================================================
    // causal.colliders(x, y)
    // =========================================================================

    @Procedure(name = ProcedureNames.COLLIDERS, mode = Mode.READ)
    @Description(
        "Return variables where effects from x and effects from y meet head-to-head. " +
        "Used by the DSL to answer COLLIDERS BETWEEN."
    )
    public Stream<VariableNodeRecord> colliders(
            @Name("x") Node x,
            @Name("y") Node y
    ) {
        return pathQuery(x, y, CausalGraphRuntime::colliders);
    }

    // =========================================================================
    // causal.confounders(x, y)
    // =========================================================================

    @Procedure(name = ProcedureNames.CONFOUNDERS, mode = Mode.READ)
    @Description(
        "Return common ancestors of x and y. Used by the DSL to answer CONFOUNDERS OF."
    )
    public Stream<VariableNodeRecord> confounders(
            @Name("x") Node x,
            @Name("y") Node y
    ) {
        return pathQuery(x, y, CausalGraphRuntime::confounders);
    }

    // =========================================================================
    // causal.backdoorVariables(treatment, outcome)
    // =========================================================================

    @Procedure(name = ProcedureNames.BACKDOOR_VARIABLES, mode = Mode.READ)
    @Description(
        "Return variables on backdoor paths from treatment to outcome. " +
        "Used by the DSL to answer BACKDOOR PATHS BETWEEN."
    )
    public Stream<VariableNodeRecord> backdoorVariables(
            @Name("treatment") Node treatment,
            @Name("outcome")   Node outcome
    ) {
        return pathQuery(treatment, outcome, CausalGraphRuntime::backdoorVariables);
    }

    // =========================================================================
    // causal.linearGaussianInference(treatment, outcome, doValues)
    // =========================================================================

    // doValuesMap is Map<String, Double> instead of Map<Node, Double>
    // cypher only allows map keys as literals
    @Procedure(name = ProcedureNames.LINEAR_GAUSSIAN_INFERENCE, mode = Mode.READ)
    @Description(
        "Run linear-Gaussian inference over the parameterized causal graph. " +
        "Returns interventional means, variances, and total effect." +
        "Use Variable node ID direclty (without quotes) in the doValues map."
    )
    public Stream<LinearGaussianRecord> linearGaussianInference(
            @Name("treatment") Node treatment,
            @Name("outcome")   Node outcome,
            @Name(value = "doValues", defaultValue = "{}") Map<String, Double> doValuesMap
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef t = ProcedureVariables.requireResolved(runtime, treatment, "treatment");
        VariableRef o = ProcedureVariables.requireResolved(runtime, outcome, "outcome");

        Map<VariableRef, Double> doValues = new HashMap<>();
        for (Map.Entry<String, Double> entry : doValuesMap.entrySet()) {
            Optional<VariableRef> ref = runtime.resolveVariable(entry.getKey());
            if (ref.isPresent()) {
                doValues.put(ref.get(), entry.getValue());
            } else {
                throw new IllegalArgumentException("Unknown variable in doValues: " + entry.getKey());
            }
        }

        return Stream.of(runtime.linearGaussianInference(t, o, doValues));
    }

    // =========================================================================
    // causal.counterfactual(interventionVar, counterfactualValue, outcome, factualValues)
    // =========================================================================

    @Procedure(name = ProcedureNames.COUNTERFACTUAL, mode = Mode.READ)
    @Description("Linear-Gaussian unit counterfactual: what would `outcome` have been "
        + "under do(interventionVar = counterfactualValue), given the factual values. "
        + "Use Variable node IDs (without quotes) as keys in the factualValues map.")
    public Stream<CounterfactualRecord> counterfactual(
            @Name("interventionVar") Node interventionVar,
            @Name("counterfactualValue") double counterfactualValue,
            @Name("outcome") Node outcome,
            @Name(value = "factualValues", defaultValue = "{}") Map<String, Double> factualValues
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef iVar = ProcedureVariables.requireResolved(runtime, interventionVar, "interventionVar");
        VariableRef oVar = ProcedureVariables.requireResolved(runtime, outcome, "outcome");

        Map<VariableRef, Double> factualValuesRef = new HashMap<>();
        for (Map.Entry<String, Double> entry : factualValues.entrySet()) {
            Optional<VariableRef> ref = runtime.resolveVariable(entry.getKey());
            if (ref.isPresent()) {
                factualValuesRef.put(ref.get(), entry.getValue());
            } else {
                throw new IllegalArgumentException("Unknown variable in factualValues: " + entry.getKey());
            }
        }

        return Stream.of(runtime.counterfactual(iVar, counterfactualValue, oVar, factualValuesRef));
    }

    // =========================================================================
    // causal.inspectSnapshot()
    // =========================================================================

    @Procedure(name = ProcedureNames.INSPECT_SNAPSHOT, mode = Mode.READ)
    @Description("Return metadata about the current compiled snapshot without recompiling.")
    public Stream<SnapshotRecord> inspectSnapshot() {
        GraphSnapshot snap = RuntimeRegistry.getInstance().currentSnapshot();
        if (snap == null) {
            return Stream.of(new SnapshotRecord(0L, List.of(), "no snapshot — call causal.compile() first",
                    SnapshotPatcher.stats()));
        }
        return Stream.of(toSnapshotRecord(snap));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    // =========================================================================
    // Id-returning variants: same answers as above, one `id` column, no node lookup.
    // For callers that only need ids (the DSL, RETURN variable.id), they skip binding
    // each result to a Neo4j node.
    // =========================================================================

    @Procedure(name = ProcedureNames.PARENT_IDS, mode = Mode.READ)
    @Description("Ids of the direct parents of each :Variable node (no node lookup).")
    public Stream<IdRecord> parentIds(@Name("variables") List<Node> variables) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toIds(runtime.parents(ProcedureVariables.resolveAll(runtime, variables)));
    }

    @Procedure(name = ProcedureNames.CHILD_IDS, mode = Mode.READ)
    @Description("Ids of the direct children of each :Variable node (no node lookup).")
    public Stream<IdRecord> childIds(@Name("variables") List<Node> variables) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toIds(runtime.children(ProcedureVariables.resolveAll(runtime, variables)));
    }

    @Procedure(name = ProcedureNames.ANCESTOR_IDS, mode = Mode.READ)
    @Description("Ids of all ancestors of each :Variable node (no node lookup).")
    public Stream<IdRecord> ancestorIds(@Name("variables") List<Node> variables) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toIds(runtime.ancestors(ProcedureVariables.resolveAll(runtime, variables)));
    }

    @Procedure(name = ProcedureNames.DESCENDANT_IDS, mode = Mode.READ)
    @Description("Ids of all descendants of each :Variable node (no node lookup).")
    public Stream<IdRecord> descendantIds(@Name("variables") List<Node> variables) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        return toIds(runtime.descendants(ProcedureVariables.resolveAll(runtime, variables)));
    }

    @Procedure(name = ProcedureNames.MEDIATOR_IDS, mode = Mode.READ)
    @Description("Ids of the mediators between from and to (no node lookup).")
    public Stream<IdRecord> mediatorIds(@Name("from") Node from, @Name("to") Node to) {
        return pathIds(from, to, CausalGraphRuntime::mediators);
    }

    @Procedure(name = ProcedureNames.COLLIDER_IDS, mode = Mode.READ)
    @Description("Ids of the colliders between x and y (no node lookup).")
    public Stream<IdRecord> colliderIds(@Name("x") Node x, @Name("y") Node y) {
        return pathIds(x, y, CausalGraphRuntime::colliders);
    }

    @Procedure(name = ProcedureNames.CONFOUNDER_IDS, mode = Mode.READ)
    @Description("Ids of the common ancestors of x and y (no node lookup).")
    public Stream<IdRecord> confounderIds(@Name("x") Node x, @Name("y") Node y) {
        return pathIds(x, y, CausalGraphRuntime::confounders);
    }

    @Procedure(name = ProcedureNames.BACKDOOR_VARIABLE_IDS, mode = Mode.READ)
    @Description("Ids of the variables on backdoor paths from treatment to outcome (no node lookup).")
    public Stream<IdRecord> backdoorVariableIds(@Name("treatment") Node treatment, @Name("outcome") Node outcome) {
        return pathIds(treatment, outcome, CausalGraphRuntime::backdoorVariables);
    }

    private Stream<IdRecord> pathIds(Node from, Node to, PathFn fn) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef a = ProcedureVariables.requireResolved(runtime, from, "from");
        VariableRef b = ProcedureVariables.requireResolved(runtime, to, "to");
        return toIds(fn.apply(runtime, a, b));
    }

    /** Same order as toVariableNodes (by id). */
    private static Stream<IdRecord> toIds(Set<VariableRef> refs) {
        return refs.stream().map(VariableRef::id).sorted().map(IdRecord::new);
    }

    @FunctionalInterface
    private interface PathFn {
        Set<VariableRef> apply(CausalGraphRuntime rt, VariableRef a, VariableRef b);
    }

    private Stream<VariableNodeRecord> pathQuery(Node from, Node to, PathFn fn) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef a = ProcedureVariables.requireResolved(runtime, from, "from");
        VariableRef b = ProcedureVariables.requireResolved(runtime, to, "to");
        return toVariableNodes(fn.apply(runtime, a, b));
    }

    /** The compiled snapshot, for fetching result nodes by element id (null if none). */
    private static CompiledCausalGraph snapshotGraph() {
        GraphSnapshot snap = RuntimeRegistry.getInstance().currentSnapshot();
        return snap instanceof CompiledCausalGraph g ? g : null;
    }

    private Stream<VariableNodeRecord> toVariableNodes(Set<VariableRef> refs) {
        GraphSnapshot snap = RuntimeRegistry.getInstance().currentSnapshot();
        CompiledCausalGraph graph = snap instanceof CompiledCausalGraph g ? g : null;
        return refs.stream()
                .sorted(Comparator.comparing(VariableRef::id))
                .map(ref -> new VariableNodeRecord(ProcedureVariables.lookup(tx, graph, ref.id())));
    }

    private static SnapshotRecord toSnapshotRecord(GraphSnapshot snap) {
        List<String> varIds = snap.variables().stream()
                .map(VariableRef::id)
                .sorted()
                .collect(Collectors.toList());
        return new SnapshotRecord(varIds.size(), varIds, snap.compiledAt().toString(), SnapshotPatcher.stats());
    }
}
