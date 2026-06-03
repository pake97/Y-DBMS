package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.exec.CypherTranslator.CypherQuery;
import com.example.causal.dsl.model.CausalModel;
import com.example.causal.dsl.model.CausalVariable;
import com.example.causal.dsl.model.ModelRegistry;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Neo4j-backed executor: translates each DSL statement to Cypher and executes
 * it against a running Neo4j server via the Bolt {@link Driver}. Structural
 * queries delegate to the {@code causal.*} procedures provided by
 * {@code causal4j-plugin}, so the Neo4j graph is the single source of truth.
 *
 * <p>DSL-side metadata that the plugin's schema does not carry (unit, role,
 * binding) is also written to a local {@link ModelRegistry} so the DSL can
 * answer {@code DESCRIBE VARIABLE} and validate adjustment sets without a
 * round-trip.
 *
 * <p>Lifecycle: caller owns the {@link Driver}. This executor does not open
 * or close it.
 */
public class Neo4jQueryExecutor implements QueryExecutor {

    private final Driver driver;
    private final String database;
    private final CypherTranslator translator = new CypherTranslator();
    private final ModelRegistry registry;

    public Neo4jQueryExecutor(Driver driver, String database, ModelRegistry registry) {
        this.driver   = driver;
        this.database = database;
        this.registry = registry;
    }

    /** Uses Neo4j's default database. */
    public Neo4jQueryExecutor(Driver driver, ModelRegistry registry) {
        this(driver, null, registry);
    }

    @Override
    public QueryResult execute(Statement stmt) {
        return switch (stmt) {
            case DefineVariableStmt  s -> defineVariable(s);
            case DefineModelStmt     s -> defineModel(s);
            case DefineDagStmt       s -> defineDag(s);

            case CausesOfStmt        s -> structural(s, () -> readVars("CAUSES OF " + s.var(),      translator.causesOf(s.var())));
            case EffectsOfStmt       s -> structural(s, () -> readVars("EFFECTS OF " + s.var(),     translator.effectsOf(s.var())));
            case AncestorsOfStmt     s -> structural(s, () -> readVars("ANCESTORS OF " + s.var(),   translator.ancestorsOf(s.var())));
            case DescendantsOfStmt   s -> structural(s, () -> readVars("DESCENDANTS OF " + s.var(), translator.descendantsOf(s.var())));
            case MediatorsBetweenStmt s -> structural(s, () -> readVars(
                "MEDIATORS BETWEEN " + s.from() + " AND " + s.to(), translator.mediators(s.from(), s.to())));
            case CollidersBetweenStmt s -> structural(s, () -> readVars(
                "COLLIDERS BETWEEN " + s.from() + " AND " + s.to(), translator.colliders(s.from(), s.to())));
            case ConfoundersOfStmt    s -> structural(s, () -> readVars(
                "CONFOUNDERS OF " + s.x() + " AND " + s.y(), translator.confounders(s.x(), s.y())));
            case BackdoorPathsStmt    s -> structural(s, () -> readVars(
                "BACKDOOR PATHS BETWEEN " + s.from() + " AND " + s.to(), translator.backdoorPaths(s.from(), s.to())));

            case IdentifyStmt        s -> structural(s, () -> identify(s));
            case EstimateStmt        s -> EstimateEvaluator.evaluate(
                registry.isCurrentDerived() ? registry.currentModel().orElseThrow() : hydrateCurrentGraph(), s);
            case InterveneStmt       s -> executeIntervene(s);
            case CounterfactualStmt  s -> CounterfactualEvaluator.evaluate(
                registry.isCurrentDerived() ? registry.currentModel().orElseThrow() : hydrateCurrentGraph(), s);

            case UseModelStmt        s -> executeUseModel(s);
            case ResetStmt           s -> executeReset(s);
            case ShowModelsStmt      s -> ModelListResult.from(registry);

            case ShowDagStmt         s -> showDag(s);
            case DescribeVariableStmt s -> describeVariable(s);
        };
    }

    // ─── Writes ──────────────────────────────────────────────────────────────

    private QueryResult defineVariable(DefineVariableStmt s) {
        runWrite(translator.defineVariable(s));
        registry.registerStandaloneVariable(new CausalVariable(
            s.name(), s.unit(), s.binding(), s.type(), s.role()));
        return new TextResult("defined variable: " + s.name());
    }

    private QueryResult defineModel(DefineModelStmt s) {
        for (VariableDecl vd : s.variables()) {
            runWrite(translator.defineVariable(new DefineVariableStmt(
                vd.name(), vd.unit(), vd.binding(), vd.type(), vd.role())));
            registry.registerStandaloneVariable(new CausalVariable(
                vd.name(), vd.unit(), vd.binding(), vd.type(), vd.role()));
        }
        runWrite(translator.defineDag(s.edges()));
        runWrite(translator.compile());
        return new TextResult("defined model: " + s.name()
            + " (" + s.variables().size() + " variables, " + s.edges().size() + " edges)");
    }

    private QueryResult defineDag(DefineDagStmt s) {
        runWrite(translator.defineDag(s.edges()));
        runWrite(translator.compile());
        return new TextResult("defined DAG: " + s.name() + " (" + s.edges().size() + " edges)");
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    private QueryResult readVars(String label, CypherQuery q) {
        Set<String> result = new LinkedHashSet<>();
        try (Session session = openSession()) {
            session.run(q.cypher(), q.params()).stream()
                .map(r -> r.get(q.yieldColumn()).asString())
                .forEach(result::add);
        }
        return new VariableSetResult(label, result);
    }

    private QueryResult identify(IdentifyStmt s) {
        CypherQuery q = translator.adjustmentCandidate(s.treatment(), s.outcome());
        try (Session session = openSession()) {
            Record row = session.run(q.cypher(), q.params()).single();
            boolean exists = row.get("adjustmentSetExists").asBoolean();
            String explanation = row.get("explanation").asString();
            List<Set<String>> sets = row.get("candidateSets").asList(outer ->
                    new LinkedHashSet<>(outer.asList(inner ->
                            inner.asNode().get("id").asString())))
                .stream().collect(Collectors.toList());
            return new IdentificationResult(
                s.treatment(), s.outcome(), exists,
                exists ? "backdoor adjustment" : "not identifiable via backdoor",
                sets, explanation);
        }
    }

    // ─── Intervention & context switching ───────────────────────────────────

    /**
     * Dispatch a structural query. While the active graph is a derived
     * intervention world it lives only DSL-side, so evaluate it in-memory; while
     * it is the base graph, delegate to the plugin against live Neo4j ({@code liveBranch}).
     */
    private QueryResult structural(Statement s, Supplier<QueryResult> liveBranch) {
        if (registry.isCurrentDerived()) {
            return StructuralEvaluator.evaluate(registry.currentModel().orElseThrow(), s);
        }
        return liveBranch.get();
    }

    private QueryResult executeIntervene(InterveneStmt s) {
        // Source world: an explicit FROM MODEL, the active derived world, or a
        // fresh hydration of the persisted base graph.
        CausalModel source;
        if (s.modelRef() != null) {
            source = registry.model(s.modelRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + s.modelRef()));
        } else if (registry.isCurrentDerived()) {
            source = registry.currentModel().orElseThrow();
        } else {
            source = hydrateCurrentGraph();
        }
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
        return new TextResult("active model: live Neo4j graph");
    }

    /**
     * Load the persisted graph from Neo4j into a {@link CausalModel} — the same
     * two queries {@link #showDag} uses (all {@code :Variable} nodes + active
     * {@code :CAUSES} edges). DSL-side metadata (unit/role/type) is recovered
     * from the registry when present.
     */
    private CausalModel hydrateCurrentGraph() {
        CausalModel model = new CausalModel("neo4j");
        try (Session session = openSession()) {
            session.run("MATCH (v:Variable) RETURN v.id AS id ORDER BY id").stream()
                .map(r -> r.get("id").asString())
                .forEach(id -> model.addVariable(
                    registry.resolveVariable(id).orElse(new CausalVariable(id, null, null, null, null))));
            session.run("MATCH (a:Variable)-[r:CAUSES]->(b:Variable) WHERE r.active = true " +
                        "RETURN a.id AS from, b.id AS to")
                .stream()
                .forEach(r -> model.addEdge(r.get("from").asString(), r.get("to").asString()));
        }
        return model;
    }

    // ─── Inspection (DSL-side, does not hit Neo4j) ───────────────────────────

    private QueryResult showDag(ShowDagStmt s) {
        // Cheap Cypher: enumerate :Variable nodes + :CAUSES edges.
        StringBuilder sb = new StringBuilder("DAG");
        if (s.name() != null) sb.append(" (").append(s.name()).append(')');
        sb.append('\n');
        try (Session session = openSession()) {
            sb.append("  Variables: ");
            sb.append(session.run("MATCH (v:Variable) RETURN v.id AS id ORDER BY id").stream()
                .map(r -> r.get("id").asString()).collect(Collectors.joining(", ")));
            sb.append("\n  Edges:\n");
            session.run("MATCH (a:Variable)-[r:CAUSES]->(b:Variable) " +
                        "WHERE r.active = true RETURN a.id AS from, b.id AS to ORDER BY from, to")
                .stream()
                .forEach(r -> sb.append("    ").append(r.get("from").asString())
                                 .append(" -> ").append(r.get("to").asString()).append('\n'));
        }
        return new TextResult(sb.toString().stripTrailing());
    }

    private QueryResult describeVariable(DescribeVariableStmt s) {
        CausalVariable v = registry.resolveVariable(s.name()).orElse(null);
        StringBuilder sb = new StringBuilder("Variable: ").append(s.name()).append('\n');
        if (v != null) {
            if (v.unit()    != null) sb.append("  unit:    ").append(v.unit()).append('\n');
            if (v.type()    != null) sb.append("  type:    ").append(v.type()).append('\n');
            if (v.role()    != null) sb.append("  role:    ").append(v.role()).append('\n');
            if (v.binding() != null) sb.append("  binding: ").append(v.binding().cypherQuery()).append('\n');
        }
        try (Session session = openSession()) {
            Record row = session.run(
                "MATCH (v:Variable {id: $id}) RETURN v.latent AS latent, v.observed AS observed",
                Map.of("id", s.name())).single();
            sb.append("  latent:  ").append(row.get("latent").asBoolean()).append('\n');
            sb.append("  observed: ").append(row.get("observed").asBoolean());
        } catch (NoSuchElementException e) {
            sb.append("  (not present in Neo4j)");
        }
        return new TextResult(sb.toString());
    }

    // ─── REPL escape hatch ───────────────────────────────────────────────────

    /**
     * Run arbitrary Cypher against the same database the DSL targets and return
     * the rows as formatted lines. This is the REPL's {@code :cypher} passthrough:
     * the DSL grammar does not parse Cypher, so this bypasses it entirely while
     * reusing this executor's session/database configuration.
     *
     * <p>Accepts reads and writes alike — uses an auto-commit transaction.
     * Returns the column header plus one line per row; an empty result yields a
     * single {@code "(no rows)"} line.
     */
    public List<String> runRawCypher(String cypher) {
        List<String> lines = new ArrayList<>();
        try (Session session = openSession()) {
            org.neo4j.driver.Result result = session.run(cypher);
            List<String> keys = result.keys();
            if (!keys.isEmpty()) lines.add(String.join(" | ", keys));
            result.stream().forEach(rec ->
                lines.add(keys.stream()
                    .map(k -> rec.get(k).toString())
                    .collect(Collectors.joining(" | "))));
        }
        if (lines.size() <= 1) lines.add("(no rows)");
        return lines;
    }

    // ─── Session plumbing ────────────────────────────────────────────────────

    private Session openSession() {
        return database != null
            ? driver.session(org.neo4j.driver.SessionConfig.forDatabase(database))
            : driver.session();
    }

    private void runWrite(CypherQuery q) {
        try (Session session = openSession()) {
            session.executeWrite(tx -> {
                tx.run(q.cypher(), q.params()).consume();
                return null;
            });
        }
    }
}
