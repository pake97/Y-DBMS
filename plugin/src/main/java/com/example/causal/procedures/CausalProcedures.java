package com.example.causal.procedures;

import com.example.causal.api.*;
import com.example.causal.dto.*;
import com.example.causal.runtime.RuntimeRegistry;
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

    @Procedure(name = "causal.compile", mode = Mode.READ)
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

    @Procedure(name = "causal.invalidate", mode = Mode.READ)
    @Description(
        "Discard the current compiled snapshot. " +
        "The next call to causal.compile() will re-read the graph from Neo4j."
    )
    public Stream<SnapshotRecord> invalidate() {
        RuntimeRegistry.getInstance().invalidate();
        return Stream.of(new SnapshotRecord(0L, List.of(), "invalidated"));
    }

    // =========================================================================
    // causal.parents(variables)
    // =========================================================================

    @Procedure(name = "causal.parents", mode = Mode.READ)
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

    @Procedure(name = "causal.children", mode = Mode.READ)
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

    @Procedure(name = "causal.ancestors", mode = Mode.READ)
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

    @Procedure(name = "causal.descendants", mode = Mode.READ)
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

    @Procedure(name = "causal.dsep", mode = Mode.READ)
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

    @Procedure(name = "causal.adjustmentCandidate", mode = Mode.READ)
    @Description(
        "Find candidate adjustment sets for identifying the causal effect of " +
        "treatment on outcome from observational data."
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
                        .map(ref -> ProcedureVariables.lookup(tx, ref.id()))
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());

        return Stream.of(new AdjustmentRecord(result.adjustmentSetExists(), sets, result.explanation()));
    }

    // =========================================================================
    // causal.mediators(from, to)
    // =========================================================================

    @Procedure(name = "causal.mediators", mode = Mode.READ)
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

    @Procedure(name = "causal.colliders", mode = Mode.READ)
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

    @Procedure(name = "causal.confounders", mode = Mode.READ)
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

    @Procedure(name = "causal.backdoorVariables", mode = Mode.READ)
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
    // causal.inspectSnapshot()
    // =========================================================================

    @Procedure(name = "causal.inspectSnapshot", mode = Mode.READ)
    @Description("Return metadata about the current compiled snapshot without recompiling.")
    public Stream<SnapshotRecord> inspectSnapshot() {
        GraphSnapshot snap = RuntimeRegistry.getInstance().currentSnapshot();
        if (snap == null) {
            return Stream.of(new SnapshotRecord(0L, List.of(), "no snapshot — call causal.compile() first"));
        }
        return Stream.of(toSnapshotRecord(snap));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private Stream<VariableNodeRecord> toVariableNodes(Set<VariableRef> refs) {
        return refs.stream()
                .sorted(Comparator.comparing(VariableRef::id))
                .map(ref -> new VariableNodeRecord(ProcedureVariables.lookup(tx, ref.id())));
    }

    private static SnapshotRecord toSnapshotRecord(GraphSnapshot snap) {
        List<String> varIds = snap.variables().stream()
                .map(VariableRef::id)
                .sorted()
                .collect(Collectors.toList());
        return new SnapshotRecord(snap.size(), varIds, snap.compiledAt().toString());
    }
}
