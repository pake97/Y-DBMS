package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.CausalModel;

import com.example.causal.api.AdjustmentSetsResult;
import com.example.causal.api.VariableRef;
import com.example.causal.dsl.model.CausalVariable;
import com.example.causal.dsl.model.VariableType;
import com.example.causal.runtime.DefaultCausalGraphRuntime;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.GraphBuilder;
import com.example.causal.runtime.model.Variable;

import java.util.*;

/**
 * Pure, in-memory evaluation of the structural DSL queries against a single
 * {@link CausalModel}, for the in-memory backend ({@link InMemoryQueryExecutor}).
 * The Neo4j backend answers on server-side worlds instead and shares only the
 * INTERVENE result builders.
 *
 * <p>No I/O, no registry — given a graph and a statement, return the result.
 */
final class StructuralEvaluator {

    private StructuralEvaluator() {}

    /**
     * Evaluate a structural query / IDENTIFY / INTERVENE-render against {@code m}.
     * For INTERVENE, {@code m} must already be the derived world G′ (graph surgery
     * applied) — this method only renders the RETURN answer, it does not mutate.
     *
     * @throws IllegalArgumentException for statements that are not structural
     *         graph queries (definitions, context switches, inspection).
     */
    static QueryResult evaluate(CausalModel m, Statement stmt) {
        return switch (stmt) {
            case CausesOfStmt        s -> vars("CAUSES OF " + s.var(),      m.parents(s.var()));
            case EffectsOfStmt       s -> vars("EFFECTS OF " + s.var(),     m.children(s.var()));
            case AncestorsOfStmt     s -> vars("ANCESTORS OF " + s.var(),   m.ancestors(s.var()));
            case DescendantsOfStmt   s -> vars("DESCENDANTS OF " + s.var(), m.descendants(s.var()));
            case MediatorsBetweenStmt s -> vars(
                "MEDIATORS BETWEEN " + s.from() + " AND " + s.to(), m.mediators(s.from(), s.to()));
            case CollidersBetweenStmt s -> vars(
                "COLLIDERS BETWEEN " + s.from() + " AND " + s.to(), m.colliders(s.from(), s.to()));
            case ConfoundersOfStmt    s -> vars(
                "CONFOUNDERS OF " + s.x() + " AND " + s.y(), m.confounders(s.x(), s.y()));
            case BackdoorPathsStmt    s -> vars(
                "BACKDOOR PATHS BETWEEN " + s.from() + " AND " + s.to(), m.backdoorVars(s.from(), s.to()));
            case IdentifyStmt        s -> identify(m, s);
            case InterveneStmt       s -> render(m, s);
            default -> throw new IllegalArgumentException(
                "Not a structural query: " + stmt.getClass().getSimpleName());
        };
    }

    private static VariableSetResult vars(String label, Set<String> result) {
        return new VariableSetResult(label, result);
    }

    /**
     * IDENTIFY with the plugin's generalized adjustment criterion (all minimal sets, observed
     * variables only), on a snapshot built from {@code model} with {@link GraphBuilder}, through
     * the same {@code DefaultCausalGraphRuntime.adjustmentSets} the Neo4j backend's
     * {@code causal.adjustmentSets} calls: both backends give the same sets, method text and
     * explanation.
     *
     * <p>This reaches past {@code com.example.causal.api} into the plugin's runtime (CLAUDE.md
     * keeps the DSL on the api); it is the in-memory backend only, and sharing the algorithm is
     * what keeps the two backends from disagreeing.
     *
     * <p>Latent means {@code TYPE latent}, as {@link CypherTranslator} persists it for the Neo4j
     * backend, not {@link com.example.causal.dsl.model.CausalVariable#isLatent()} (which also
     * counts a variable without an {@code OBSERVED AS} binding).
     */
    private static IdentificationResult identify(CausalModel model, IdentifyStmt s) {
        List<Variable> vars = new ArrayList<>();
        List<String[]> edges = new ArrayList<>();
        for (CausalVariable v : model.variables().values()) {
            boolean latent = v.type() == VariableType.LATENT;
            vars.add(Variable.from(v.name(), v.name(), !latent, latent, 0.0, 1.0));
            for (String c : model.children(v.name())) edges.add(new String[]{v.name(), c});
        }
        CompiledCausalGraph g = GraphBuilder.build(vars, edges, Map.of());
        AdjustmentSetsResult r = DefaultCausalGraphRuntime.view(g).adjustmentSets(
            new VariableRef(s.treatment()), new VariableRef(s.outcome()), "all-minimal", CypherTranslator.IDENTIFY_LIMIT);
        List<Set<String>> sets = new ArrayList<>();
        for (Set<VariableRef> set : r.sets()) {
            Set<String> ids = new TreeSet<>();
            set.forEach(ref -> ids.add(ref.id()));
            sets.add(new LinkedHashSet<>(ids));
        }
        return new IdentificationResult(s.treatment(), s.outcome(), r.identifiable(), identifyMethod(r.identifiable()),
            sets, r.explanation());
    }

    /** IDENTIFY's method text, the same on both backends. */
    static String identifyMethod(boolean identifiable) {
        return identifiable ? "identifiable by adjustment (generalized criterion)" : "not identifiable by adjustment";
    }

    /**
     * Render the RETURN answer of an INTERVENE against the already-derived world G′.
     * Structural always (does do(x) reach y, and the pathway); plus numeric answers
     * when G′ is a parameterized linear-Gaussian model with numeric do-values.
     */
    private static InterventionResult render(CausalModel gPrime, InterveneStmt s) {
        String x = s.intervention().variable();
        String y = s.returnVar();

        boolean affects = gPrime.descendants(x).contains(y);
        Set<String> pathway = affects ? gPrime.mediators(x, y) : Set.of();

        boolean numeric = gPrime.isParameterized() && LinearGaussianSEM.hasNumericInterventions(gPrime);
        if (numeric) {
            LinearGaussianSEM sem = new LinearGaussianSEM(gPrime);
            Double variance = s.returnType().equals("DISTRIBUTION") ? sem.variances().get(y) : null;
            return numericResult(s, affects, pathway, gPrime.name(), sem.means().get(y), variance, sem.totalEffect(x, y));
        }
        return structuralResult(s, affects, pathway, gPrime.name());
    }

    /** INTERVENE answer with linear-Gaussian numbers (shared with {@link Neo4jQueryExecutor}). */
    static InterventionResult numericResult(InterveneStmt s, boolean affects, Set<String> pathway, String world,
                                            double expectation, Double variance, double totalEffect) {
        String x = s.intervention().variable();
        Double compareDelta = null;
        String compareNote = null;
        if (s.compareTo() != null) {
            double v2 = Double.parseDouble(s.compareTo().value());
            compareDelta = totalEffect * (Double.parseDouble(s.intervention().value()) - v2);
            compareNote = "linear-Gaussian: contrast = totalEffect·(v − v₂) = " + compareDelta;
        }
        return new InterventionResult(x, s.intervention().value(), s.returnVar(), s.returnType(), affects, pathway,
            world, null, compareNote, expectation, variance, totalEffect, compareDelta);
    }

    /** INTERVENE answer without parameters (shared with {@link Neo4jQueryExecutor}). */
    static InterventionResult structuralResult(InterveneStmt s, boolean affects, Set<String> pathway, String world) {
        String x = s.intervention().variable();
        String v = s.intervention().value();
        String y = s.returnVar();
        // Structural-only fallback (no declared parameters, or non-numeric do-value).
        String note = switch (s.returnType()) {
            case "EFFECT" -> "Numeric magnitude of the effect requires the parameter layer (not implemented in v1).";
            default       -> "Structural form only; numeric " + s.returnType()
                              + " requires the parameter layer (not implemented in v1).";
        };
        String compareNote = null;
        if (s.compareTo() != null) {
            String v2 = s.compareTo().value();
            compareNote = "COMPARE " + x + "=" + v + " vs " + x + "=" + v2
                + ": identical causal structure (surgery is value-independent); "
                + "numeric contrast E[" + y + "|do(" + x + "=" + v + ")] − E[" + y + "|do(" + x + "=" + v2 + ")] "
                + "requires the parameter layer.";
        }
        return new InterventionResult(x, v, y, s.returnType(), affects, pathway, world,
            note, compareNote, null, null, null, null);
    }
}
