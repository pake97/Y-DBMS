package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory executor: stores DAGs in a ModelRegistry and runs structural
 * algorithms directly on CausalModel. Fast, no Neo4j required — ideal for
 * offline use and unit tests.
 *
 * <p>For production, use {@link Neo4jQueryExecutor} — it translates the same
 * AST to Cypher and delegates to the {@code causal.*} plugin procedures, so
 * the Neo4j-persisted graph is the single source of truth.
 */
public class InMemoryQueryExecutor implements QueryExecutor {

    private final ModelRegistry registry;

    public InMemoryQueryExecutor(ModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public QueryResult execute(Statement stmt) {
        return switch (stmt) {
            case DefineVariableStmt  s -> executeDefineVariable(s);
            case DefineModelStmt     s -> executeDefineModel(s);
            case DefineDagStmt       s -> executeDefineDag(s);
            case CausesOfStmt        s -> structural("CAUSES OF " + s.var(),      m -> m.parents(s.var()));
            case EffectsOfStmt       s -> structural("EFFECTS OF " + s.var(),     m -> m.children(s.var()));
            case AncestorsOfStmt     s -> structural("ANCESTORS OF " + s.var(),   m -> m.ancestors(s.var()));
            case DescendantsOfStmt   s -> structural("DESCENDANTS OF " + s.var(), m -> m.descendants(s.var()));
            case MediatorsBetweenStmt s -> structural("MEDIATORS BETWEEN " + s.from() + " AND " + s.to(), m -> m.mediators(s.from(), s.to()));
            case CollidersBetweenStmt s -> structural("COLLIDERS BETWEEN " + s.from() + " AND " + s.to(), m -> m.colliders(s.from(), s.to()));
            case ConfoundersOfStmt    s -> structural("CONFOUNDERS OF " + s.x() + " AND " + s.y(), m -> m.confounders(s.x(), s.y()));
            case BackdoorPathsStmt    s -> structural("BACKDOOR PATHS BETWEEN " + s.from() + " AND " + s.to(), m -> m.backdoorVars(s.from(), s.to()));
            case IdentifyStmt        s -> executeIdentify(s);
            case EstimateStmt        s -> executeEstimate(s);
            case InterveneStmt       s -> new TextResult("INTERVENE not yet implemented in v1");
            case CounterfactualStmt  s -> new TextResult("COUNTERFACTUAL not yet implemented in v1");
            case ShowDagStmt         s -> executeShowDag(s);
            case DescribeVariableStmt s -> executeDescribeVariable(s);
        };
    }

    // ─── Definitions ──────────────────────────────────────────────────────────

    private QueryResult executeDefineVariable(DefineVariableStmt s) {
        registry.registerStandaloneVariable(new CausalVariable(
            s.name(), s.unit(), s.binding(), s.type(), s.role()
        ));
        return new TextResult("defined variable: " + s.name());
    }

    private QueryResult executeDefineModel(DefineModelStmt s) {
        CausalModel model = new CausalModel(s.name());
        for (VariableDecl vd : s.variables()) {
            model.addVariable(new CausalVariable(vd.name(), vd.unit(), vd.binding(), vd.type(), vd.role()));
        }
        // Merge in any standalone variables whose names appear in the DAG edges
        Set<String> edgeVarNames = new HashSet<>();
        for (DagEdge e : s.edges()) { edgeVarNames.add(e.from()); edgeVarNames.add(e.to()); }
        registry.standaloneVariables().forEach((name, v) -> {
            if (edgeVarNames.contains(name) && model.variables().get(name) == null)
                model.addVariable(v);
        });
        for (DagEdge e : s.edges()) model.addEdge(e.from(), e.to());
        registry.registerModel(model);
        return new TextResult("defined model: " + s.name() + " (" + s.edges().size() + " edges)");
    }

    private QueryResult executeDefineDag(DefineDagStmt s) {
        CausalModel model = new CausalModel(s.name());
        // Pull in previously defined standalone variables
        for (DagEdge e : s.edges()) {
            CausalVariable from = registry.standaloneVariables().get(e.from());
            CausalVariable to   = registry.standaloneVariables().get(e.to());
            if (from != null) model.addVariable(from);
            if (to   != null) model.addVariable(to);
            model.addEdge(e.from(), e.to());
        }
        registry.registerModel(model);
        return new TextResult("defined DAG: " + s.name() + " (" + s.edges().size() + " edges)");
    }

    // ─── Structural queries ───────────────────────────────────────────────────

    private QueryResult structural(String label, Function<CausalModel, Set<String>> fn) {
        CausalModel model = requireCurrentModel();
        return new VariableSetResult(label, fn.apply(model));
    }

    // ─── Identification ───────────────────────────────────────────────────────

    private QueryResult executeIdentify(IdentifyStmt s) {
        CausalModel model = requireCurrentModel();
        String t = s.treatment();
        String o = s.outcome();

        // Backdoor criterion: find adjustment sets that block all backdoor paths.
        // Minimal valid set = confounders (common causes) minus descendants of treatment.
        Set<String> descTreatment = model.descendants(t);
        Set<String> backdoor = model.backdoorVars(t, o);
        Set<String> confounders = model.confounders(t, o);
        confounders.removeAll(descTreatment);

        // Also consider: parents of treatment that cause outcome (direct confounding).
        Set<String> directConfounders = new HashSet<>(model.parents(t));
        directConfounders.retainAll(model.ancestors(o));
        directConfounders.addAll(confounders);
        directConfounders.removeAll(descTreatment);

        boolean hasBackdoor = !backdoor.isEmpty();
        boolean identifiable;
        String method;
        List<Set<String>> adjustmentSets;

        if (!hasBackdoor) {
            identifiable = true;
            method = "no backdoor paths — no adjustment required";
            adjustmentSets = List.of(Set.of());
        } else if (!directConfounders.isEmpty()) {
            identifiable = true;
            method = "backdoor adjustment";
            adjustmentSets = List.of(Set.copyOf(directConfounders));
        } else {
            // Backdoor paths exist but no observed adjustment set found
            identifiable = false;
            method = "not identifiable via backdoor criterion with observed variables";
            adjustmentSets = List.of();
        }

        String explanation = hasBackdoor
            ? "Backdoor variables: " + backdoor
            : "No backdoor paths from " + t + " to " + o;

        return new IdentificationResult(t, o, identifiable, method, adjustmentSets, explanation);
    }

    // ─── Estimation ───────────────────────────────────────────────────────────

    private QueryResult executeEstimate(EstimateStmt s) {
        // For v1: validate the adjustment set structurally, then stub the estimation.
        CausalModel model = requireCurrentModel();
        String t = s.treatment();
        String o = s.outcome();
        Set<String> descTreatment = model.descendants(t);

        List<String> invalid = s.adjustFor().stream()
            .filter(v -> descTreatment.contains(v))
            .collect(Collectors.toList());
        if (!invalid.isEmpty()) {
            return new TextResult("ERROR: adjustment set contains descendants of " + t + ": " + invalid
                + ". Conditioning on descendants of treatment opens collider bias.");
        }

        String estimator = s.estimator() != null ? s.estimator() : "regression";
        String adjustStr = s.adjustFor().isEmpty() ? "(none)" : String.join(", ", s.adjustFor());
        return new TextResult(
            "ESTIMATE EFFECT OF " + t + " ON " + o + "\n"
            + "  adjust for: " + adjustStr + "\n"
            + "  estimator:  " + estimator + "\n"
            + "  [estimation requires ObservationBinding execution — not yet implemented in v1]"
        );
    }

    // ─── Inspection ───────────────────────────────────────────────────────────

    private QueryResult executeShowDag(ShowDagStmt s) {
        CausalModel model = s.name() != null
            ? registry.model(s.name()).orElseThrow(() -> new IllegalArgumentException("Unknown model: " + s.name()))
            : requireCurrentModel();

        StringBuilder sb = new StringBuilder("DAG: ").append(model.name()).append("\n");
        sb.append("  Variables: ").append(String.join(", ", model.variables().keySet())).append("\n");
        sb.append("  Edges:\n");
        for (String[] e : model.edgeList())
            sb.append("    ").append(e[0]).append(" -> ").append(e[1]).append("\n");
        return new TextResult(sb.toString().stripTrailing());
    }

    private QueryResult executeDescribeVariable(DescribeVariableStmt s) {
        CausalVariable v = registry.resolveVariable(s.name())
            .orElseThrow(() -> new IllegalArgumentException("Unknown variable: " + s.name()));
        StringBuilder sb = new StringBuilder("Variable: ").append(v.name()).append("\n");
        if (v.unit()    != null) sb.append("  unit:    ").append(v.unit()).append("\n");
        if (v.type()    != null) sb.append("  type:    ").append(v.type()).append("\n");
        if (v.role()    != null) sb.append("  role:    ").append(v.role()).append("\n");
        sb.append("  latent:  ").append(v.isLatent()).append("\n");
        if (v.binding() != null) sb.append("  binding: ").append(v.binding().cypherQuery());
        return new TextResult(sb.toString().stripTrailing());
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private CausalModel requireCurrentModel() {
        return registry.currentModel()
            .orElseThrow(() -> new IllegalStateException(
                "No model defined. Use DEFINE CAUSAL DAG or DEFINE MODEL first."));
    }
}
