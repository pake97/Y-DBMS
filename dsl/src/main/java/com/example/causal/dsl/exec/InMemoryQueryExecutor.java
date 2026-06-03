package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.*;

import java.util.*;

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
            case CausesOfStmt        s -> structural(s);
            case EffectsOfStmt       s -> structural(s);
            case AncestorsOfStmt     s -> structural(s);
            case DescendantsOfStmt   s -> structural(s);
            case MediatorsBetweenStmt s -> structural(s);
            case CollidersBetweenStmt s -> structural(s);
            case ConfoundersOfStmt    s -> structural(s);
            case BackdoorPathsStmt    s -> structural(s);
            case IdentifyStmt        s -> structural(s);
            case EstimateStmt        s -> EstimateEvaluator.evaluate(requireCurrentModel(), s);
            case InterveneStmt       s -> executeIntervene(s);
            case CounterfactualStmt  s -> CounterfactualEvaluator.evaluate(requireCurrentModel(), s);
            case UseModelStmt        s -> executeUseModel(s);
            case ResetStmt           s -> executeReset(s);
            case ShowModelsStmt      s -> ModelListResult.from(registry);
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
            // Linear-Gaussian parameters are honored inside DEFINE MODEL blocks.
            if (vd.mean()  != null) model.setIntercept(vd.name(), vd.mean());
            if (vd.noise() != null) model.setNoise(vd.name(), vd.noise());
        }
        // Merge in any standalone variables whose names appear in the DAG edges
        Set<String> edgeVarNames = new HashSet<>();
        for (DagEdge e : s.edges()) { edgeVarNames.add(e.from()); edgeVarNames.add(e.to()); }
        registry.standaloneVariables().forEach((name, v) -> {
            if (edgeVarNames.contains(name) && model.variables().get(name) == null)
                model.addVariable(v);
        });
        for (DagEdge e : s.edges()) addEdge(model, e);
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
            addEdge(model, e);
        }
        registry.registerModel(model);
        return new TextResult("defined DAG: " + s.name() + " (" + s.edges().size() + " edges)");
    }

    /** Add an edge and, when a WEIGHT was declared, its linear-Gaussian coefficient. */
    private static void addEdge(CausalModel model, DagEdge e) {
        model.addEdge(e.from(), e.to());
        if (e.weight() != null) model.setWeight(e.from(), e.to(), e.weight());
    }

    // ─── Structural queries + identification ────────────────────────────────────

    /** All structural queries and IDENTIFY run against the active graph via the shared evaluator. */
    private QueryResult structural(Statement s) {
        return StructuralEvaluator.evaluate(requireCurrentModel(), s);
    }

    // ─── Intervention (the do-operator) ─────────────────────────────────────────

    private QueryResult executeIntervene(InterveneStmt s) {
        CausalModel source = s.modelRef() != null
            ? registry.model(s.modelRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + s.modelRef()))
            : requireCurrentModel();
        String derivedName = CausalModel.derivedName(
            source.name(), s.intervention().variable(), s.intervention().value());
        CausalModel gPrime = source.intervene(
            s.intervention().variable(), s.intervention().value(), derivedName);
        registry.registerDerived(gPrime);   // G′ becomes the active graph
        return StructuralEvaluator.evaluate(gPrime, s);
    }

    private QueryResult executeUseModel(UseModelStmt s) {
        registry.useModel(s.modelName());
        return new TextResult("active model: " + s.modelName());
    }

    private QueryResult executeReset(ResetStmt s) {
        registry.reset();
        return new TextResult("active model: "
            + registry.currentModel().map(CausalModel::name).orElse("(none)"));
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
