package com.example.causal.dsl.exec;

import com.example.causal.api.ResultColumns;
import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.exec.CypherTranslator.CypherQuery;
import com.example.causal.dsl.model.CausalModel;
import com.example.causal.dsl.model.CausalVariable;
import com.example.causal.dsl.model.ModelRegistry;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j-backed executor: translates each DSL statement to Cypher and executes
 * it against a running Neo4j server via the Bolt {@link Driver}. Structural
 * queries delegate to the {@code causal.*} procedures provided by
 * {@code ydbms-plugin}, so the Neo4j graph is the single source of truth.
 *
 * <p>Worlds live on the server ({@code causal.intervene}, {@code causal.use},
 * {@code causal.models}): {@code INTERVENE} defines a derived world there, and every
 * structural statement and {@code IDENTIFY} is answered by the procedures on the
 * active world, with no client-side graph. The executor remembers only the do-values
 * of the worlds it defined, for the numeric answers ({@code INTERVENE} on a
 * parameterized graph) and for {@code ESTIMATE}/{@code COUNTERFACTUAL}, which still
 * evaluate DSL-side on the stored graph with those interventions replayed.
 *
 * <p>DSL-side metadata that the plugin's schema does not carry (unit, role,
 * binding) is also written to a local {@link ModelRegistry} so the DSL can
 * answer {@code DESCRIBE VARIABLE} without a round-trip.
 *
 * <p>Lifecycle: caller owns the {@link Driver}. This executor does not open
 * or close it.
 */
public class Neo4jQueryExecutor implements QueryExecutor {

    private final Driver driver;
    private final String database;
    private final CypherTranslator translator = new CypherTranslator();
    private final ModelRegistry registry;
    /** The world this executor reads ({@link CypherTranslator#BASE_WORLD} until INTERVENE or USE MODEL). */
    private String active = CypherTranslator.BASE_WORLD;
    /** do-values (variable -> value, in order) of each world this executor defined; base has none. */
    private final Map<String, LinkedHashMap<String, String>> doValues = new HashMap<>();

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

            case CausesOfStmt        s -> readVars("CAUSES OF " + s.var(),      translator.causesOf(s.var(), active));
            case EffectsOfStmt       s -> readVars("EFFECTS OF " + s.var(),     translator.effectsOf(s.var(), active));
            case AncestorsOfStmt     s -> readVars("ANCESTORS OF " + s.var(),   translator.ancestorsOf(s.var(), active));
            case DescendantsOfStmt   s -> readVars("DESCENDANTS OF " + s.var(), translator.descendantsOf(s.var(), active));
            case MediatorsBetweenStmt s -> readVars(
                "MEDIATORS BETWEEN " + s.from() + " AND " + s.to(), translator.mediators(s.from(), s.to(), active));
            case CollidersBetweenStmt s -> readVars(
                "COLLIDERS BETWEEN " + s.from() + " AND " + s.to(), translator.colliders(s.from(), s.to(), active));
            case ConfoundersOfStmt    s -> readVars(
                "CONFOUNDERS OF " + s.x() + " AND " + s.y(), translator.confounders(s.x(), s.y(), active));
            case BackdoorPathsStmt    s -> readVars(
                "BACKDOOR PATHS BETWEEN " + s.from() + " AND " + s.to(), translator.backdoorPaths(s.from(), s.to(), active));

            case IdentifyStmt        s -> identify(s);
            case EstimateStmt        s -> EstimateEvaluator.evaluate(activeModelDslSide(), s);
            case InterveneStmt       s -> executeIntervene(s);
            case CounterfactualStmt  s -> CounterfactualEvaluator.evaluate(activeModelDslSide(), s);

            case UseModelStmt        s -> executeUseModel(s);
            case ResetStmt           s -> executeReset(s);
            case ShowModelsStmt      s -> showModels();

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
            runWrite(translator.defineVariable(vd));   // persists intercept/noise too
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
        return new VariableSetResult(label, readIds(q));
    }

    private Set<String> readIds(CypherQuery q) {
        Set<String> result = new LinkedHashSet<>();
        try (Session session = openSession()) {
            session.run(q.cypher(), q.params()).stream()
                .map(r -> r.get(q.yieldColumn()).asString())
                .forEach(result::add);
        }
        return result;
    }

    private QueryResult identify(IdentifyStmt s) {
        CypherQuery q = translator.adjustmentSets(s.treatment(), s.outcome(), CypherTranslator.IDENTIFY_LIMIT, active);
        try (Session session = openSession()) {
            Record row = session.run(q.cypher(), q.params()).single();
            boolean identifiable = row.get("identifiable").asBoolean();
            List<Set<String>> sets = row.get("sets").asList(set ->
                    (Set<String>) new LinkedHashSet<>(set.asList(org.neo4j.driver.Value::asString)));
            return new IdentificationResult(
                s.treatment(), s.outcome(), identifiable,
                StructuralEvaluator.identifyMethod(identifiable),
                sets, row.get("explanation").asString());
        }
    }

    // ─── Intervention & context switching (server-side worlds) ──────────────

    /**
     * {@code INTERVENE ON x = v [FROM MODEL m]}: define {@code do(x)} on the source world
     * (m, else the active one) as a server-side world named like the in-memory backend's
     * ({@code <source>_do_<x>_<v>}, with {@code neo4j} for the stored graph), make it active,
     * and answer the RETURN part with procedures on it.
     */
    private QueryResult executeIntervene(InterveneStmt s) {
        String x = s.intervention().variable();
        String value = s.intervention().value();
        String y = s.returnVar();
        String source = s.modelRef() != null ? s.modelRef() : active;
        String sourceLabel = CypherTranslator.BASE_WORLD.equals(source) ? "neo4j" : source;
        String world = CausalModel.derivedName(sourceLabel, x, value);

        try (Session session = openSession()) {
            CypherQuery def = translator.intervene(world, x, source);
            session.run(def.cypher(), def.params()).consume();
            CypherQuery use = translator.use(world);
            session.run(use.cypher(), use.params()).consume();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>(doValues.getOrDefault(source, new LinkedHashMap<>()));
        values.put(x, value);
        doValues.put(world, values);
        active = world;

        boolean affects = readIds(translator.descendantsOf(x, world)).contains(y);
        Set<String> pathway = affects ? readIds(translator.mediators(x, y, world)) : Set.of();

        Map<String, Double> numericValues = new LinkedHashMap<>();
        values.forEach((var, v) -> { Double d = tryParseDouble(v); if (d != null) numericValues.put(var, d); });
        if (numericValues.size() == values.size() && storedGraphIsParameterized()) {
            try {
                return numericIntervene(s, affects, pathway, world, numericValues, world);
            } catch (org.neo4j.driver.exceptions.ClientException e) {
                if (!String.valueOf(e.getMessage()).contains("not parameterized")) throw e;
                // Every parameter sat on a cut edge, so the derived world has none: do() on the
                // stored graph with all the do-values gives the same means.
                return numericIntervene(s, affects, pathway, world, numericValues, CypherTranslator.BASE_WORLD);
            }
        }
        return StructuralEvaluator.structuralResult(s, affects, pathway, world);
    }

    private InterventionResult numericIntervene(InterveneStmt s, boolean affects, Set<String> pathway, String world,
                                                Map<String, Double> doValues, String onModel) {
        String x = s.intervention().variable(), y = s.returnVar();
        CypherQuery q = translator.linearGaussianInference(x, y, doValues, onModel);
        try (Session session = openSession()) {
            Record row = session.run(q.cypher(), q.params()).single();
            Double variance = "DISTRIBUTION".equals(s.returnType())
                ? row.get(ResultColumns.VARIANCES).get(y).asDouble() : null;
            return StructuralEvaluator.numericResult(s, affects, pathway, world,
                row.get(ResultColumns.MEANS).get(y).asDouble(), variance,
                row.get(ResultColumns.TOTAL_EFFECT).asDouble());
        }
    }

    /**
     * The stored graph carries linear-Gaussian parameters: an intercept other than 0, a noise
     * other than 1, or an active edge weight other than 1 (the plugin's and
     * {@link CausalModel#isParameterized()}'s definition).
     */
    private boolean storedGraphIsParameterized() {
        try (Session session = openSession()) {
            return session.run(
                "RETURN EXISTS { MATCH (v:Variable) WHERE coalesce(v.intercept, 0.0) <> 0.0 OR coalesce(v.noise, 1.0) <> 1.0 } " +
                "    OR EXISTS { MATCH (:Variable)-[r:CAUSES]->(:Variable) " +
                "                WHERE coalesce(r.active, true) AND coalesce(r.weight, 1.0) <> 1.0 } AS p")
                .single().get("p").asBoolean();
        }
    }

    private static Double tryParseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private QueryResult executeUseModel(UseModelStmt s) {
        useWorld(s.modelName());
        return new TextResult("active model: " + s.modelName());
    }

    private QueryResult executeReset(ResetStmt s) {
        useWorld(CypherTranslator.BASE_WORLD);
        return new TextResult("active model: live Neo4j graph");
    }

    private void useWorld(String name) {
        CypherQuery q = translator.use(name);
        try (Session session = openSession()) {
            session.run(q.cypher(), q.params()).consume();
        }
        active = name;
    }

    private QueryResult showModels() {
        CypherQuery q = translator.models();
        List<ModelListResult.Entry> entries = new ArrayList<>();
        String activeName = null;
        try (Session session = openSession()) {
            for (Record r : session.run(q.cypher(), q.params()).list()) {
                String name = r.get(ResultColumns.NAME).asString();
                boolean isActive = r.get(ResultColumns.ACTIVE).asBoolean();
                if (isActive) activeName = name;
                entries.add(new ModelListResult.Entry(name, isActive, !r.get(ResultColumns.BASE).isNull()));
            }
        }
        return new ModelListResult(entries, activeName);
    }

    /**
     * The active world as a DSL-side {@link CausalModel}, for ESTIMATE and COUNTERFACTUAL
     * (still evaluated DSL-side): the stored graph, with the active world's do-values
     * replayed when it is a derived world this executor defined.
     */
    private CausalModel activeModelDslSide() {
        CausalModel m = hydrateCurrentGraph();
        if (CypherTranslator.BASE_WORLD.equals(active)) return m;
        Map<String, String> values = doValues.get(active);
        if (values == null) {
            throw new IllegalStateException("world `" + active + "` was not defined by this session's INTERVENE, "
                + "so its do-values are unknown; ESTIMATE and COUNTERFACTUAL need them");
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            m = m.intervene(e.getKey(), e.getValue(), CausalModel.derivedName(m.name(), e.getKey(), e.getValue()));
        }
        return m;
    }

    /**
     * Load the persisted graph from Neo4j into a {@link CausalModel} (for ESTIMATE and
     * COUNTERFACTUAL only) — the same
     * two queries {@link #showDag} uses (all {@code :Variable} nodes + active
     * {@code :CAUSES} edges). DSL-side metadata (unit/role/type) is recovered
     * from the registry when present.
     */
    private CausalModel hydrateCurrentGraph() {
        CausalModel model = new CausalModel("neo4j");
        try (Session session = openSession()) {
            // Load nodes with their linear-Gaussian parameters. Only values that
            // deviate from the SEM defaults (intercept 0.0, noise 1.0) are set, so
            // model.isParameterized() matches the plugin's own definition exactly —
            // a graph is "parameterized" for one iff it is for the other.
            session.run("MATCH (v:Variable) " +
                        "RETURN v.id AS id, coalesce(v.intercept, 0.0) AS intercept, " +
                        "       coalesce(v.noise, 1.0) AS noise ORDER BY id")
                .stream()
                .forEach(r -> {
                    String id = r.get("id").asString();
                    model.addVariable(registry.resolveVariable(id)
                        .orElse(new CausalVariable(id, null, null, null, null)));
                    double intercept = r.get("intercept").asDouble();
                    double noise = r.get("noise").asDouble();
                    if (intercept != 0.0) model.setIntercept(id, intercept);
                    if (noise != 1.0)     model.setNoise(id, noise);
                });
            session.run("MATCH (a:Variable)-[r:CAUSES]->(b:Variable) WHERE coalesce(r.active, true) = true " +
                        "RETURN a.id AS from, b.id AS to, coalesce(r.weight, 1.0) AS weight")
                .stream()
                .forEach(r -> {
                    String from = r.get("from").asString();
                    String to   = r.get("to").asString();
                    model.addEdge(from, to);
                    double weight = r.get("weight").asDouble();
                    if (weight != 1.0) model.setWeight(from, to, weight);
                });
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
                        "WHERE coalesce(r.active, true) = true RETURN a.id AS from, b.id AS to ORDER BY from, to")
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
