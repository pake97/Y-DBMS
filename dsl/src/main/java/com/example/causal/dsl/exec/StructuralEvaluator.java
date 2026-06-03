package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.*;
import com.example.causal.dsl.model.CausalModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, in-memory evaluation of the structural DSL queries against a single
 * {@link CausalModel}. Shared by both executors: {@link InMemoryQueryExecutor}
 * always evaluates here, and {@link Neo4jQueryExecutor} routes queries here when
 * the active graph is a derived intervention world (the plugin only knows the
 * persisted base graph).
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
     * Backdoor-criterion identification. (Moved verbatim from the in-memory
     * executor so both paths share one implementation.)
     */
    private static IdentificationResult identify(CausalModel model, IdentifyStmt s) {
        String t = s.treatment();
        String o = s.outcome();

        Set<String> descTreatment = model.descendants(t);
        Set<String> backdoor = model.backdoorVars(t, o);
        Set<String> confounders = model.confounders(t, o);
        confounders.removeAll(descTreatment);

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
            identifiable = false;
            method = "not identifiable via backdoor criterion with observed variables";
            adjustmentSets = List.of();
        }

        String explanation = hasBackdoor
            ? "Backdoor variables: " + backdoor
            : "No backdoor paths from " + t + " to " + o;

        return new IdentificationResult(t, o, identifiable, method, adjustmentSets, explanation);
    }

    /**
     * Render the RETURN answer of an INTERVENE against the already-derived world
     * G′. Structural only: reports whether do(x) reaches y and the causal pathway.
     */
    private static InterventionResult render(CausalModel gPrime, InterveneStmt s) {
        String x = s.intervention().variable();
        String v = s.intervention().value();
        String y = s.returnVar();

        boolean affects = gPrime.descendants(x).contains(y);
        Set<String> pathway = affects ? gPrime.mediators(x, y) : Set.of();

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

        return new InterventionResult(x, v, y, s.returnType(), affects, pathway, gPrime.name(), note, compareNote);
    }
}
