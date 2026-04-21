package com.example.causal.procedures;

import com.example.causal.api.*;
import com.example.causal.dto.*;
import com.example.causal.runtime.RuntimeRegistry;
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
 *   <li>The {@link Transaction} injected by Neo4j is passed to {@link GraphCompileOptions}
 *       only for {@code compile()} — all other procedures operate on the cached snapshot.</li>
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
    // causal.parents(list<string>)
    // =========================================================================

    @Procedure(name = "causal.parents", mode = Mode.READ)
    @Description("Return the direct parents (immediate causes) of each named variable.")
    public Stream<VariableNameRecord> parents(
            @Name("variableIds") List<String> variableIds
    ) {
        return RuntimeRegistry.getInstance()
                .parents(resolveAll(variableIds))
                .stream()
                .map(v -> new VariableNameRecord(v.id()));
    }

    // =========================================================================
    // causal.children(list<string>)
    // =========================================================================

    @Procedure(name = "causal.children", mode = Mode.READ)
    @Description("Return the direct children (immediate effects) of each named variable.")
    public Stream<VariableNameRecord> children(
            @Name("variableIds") List<String> variableIds
    ) {
        return RuntimeRegistry.getInstance()
                .children(resolveAll(variableIds))
                .stream()
                .map(v -> new VariableNameRecord(v.id()));
    }

    // =========================================================================
    // causal.ancestors(list<string>)
    // =========================================================================

    @Procedure(name = "causal.ancestors", mode = Mode.READ)
    @Description("Return all ancestors (transitive causes) of each named variable.")
    public Stream<VariableNameRecord> ancestors(
            @Name("variableIds") List<String> variableIds
    ) {
        return RuntimeRegistry.getInstance()
                .ancestors(resolveAll(variableIds))
                .stream()
                .map(v -> new VariableNameRecord(v.id()));
    }

    // =========================================================================
    // causal.descendants(list<string>)
    // =========================================================================

    @Procedure(name = "causal.descendants", mode = Mode.READ)
    @Description("Return all descendants (transitive effects) of each named variable.")
    public Stream<VariableNameRecord> descendants(
            @Name("variableIds") List<String> variableIds
    ) {
        return RuntimeRegistry.getInstance()
                .descendants(resolveAll(variableIds))
                .stream()
                .map(v -> new VariableNameRecord(v.id()));
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
            @Name("x") List<String> x,
            @Name("y") List<String> y,
            @Name("z") List<String> z
    ) {
        DSeparationResult result = RuntimeRegistry.getInstance()
                .isDSeparated(resolveAll(x), resolveAll(y), resolveAll(z));
        return Stream.of(new DSeparationRecord(result.separated(), result.explanation()));
    }

    // =========================================================================
    // causal.adjustmentCandidate(treatment, outcome)
    // =========================================================================

    @Procedure(name = "causal.adjustmentCandidate", mode = Mode.READ)
    @Description(
        "Find candidate adjustment sets for identifying the causal effect of " +
        "'treatment' on 'outcome' from observational data."
    )
    public Stream<AdjustmentRecord> adjustmentCandidate(
            @Name("treatment") String treatment,
            @Name("outcome")   String outcome
    ) {
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        VariableRef t = runtime.resolveVariable(treatment)
                .orElseThrow(() -> new IllegalArgumentException("Unknown variable: " + treatment));
        VariableRef o = runtime.resolveVariable(outcome)
                .orElseThrow(() -> new IllegalArgumentException("Unknown variable: " + outcome));

        AdjustmentCandidateResult result = runtime.candidateAdjustmentSet(t, o);

        List<List<String>> sets = result.candidateSets().stream()
                .map(s -> s.stream().map(VariableRef::id).sorted().collect(Collectors.toList()))
                .collect(Collectors.toList());

        return Stream.of(new AdjustmentRecord(result.adjustmentSetExists(), sets, result.explanation()));
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

    private Set<VariableRef> resolveAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        CausalGraphRuntime runtime = RuntimeRegistry.getInstance();
        Set<VariableRef> result = new LinkedHashSet<>();
        for (String id : ids) {
            runtime.resolveVariable(id).ifPresent(result::add);
        }
        return result;
    }

    private static SnapshotRecord toSnapshotRecord(GraphSnapshot snap) {
        List<String> varIds = snap.variables().stream()
                .map(VariableRef::id)
                .sorted()
                .collect(Collectors.toList());
        return new SnapshotRecord(snap.size(), varIds, snap.compiledAt().toString());
    }
}
