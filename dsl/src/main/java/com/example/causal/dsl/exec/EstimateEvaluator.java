package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.EstimateStmt;
import com.example.causal.dsl.model.CausalModel;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates {@code ESTIMATE} against a {@link CausalModel}. Validates the
 * adjustment set structurally, then — per the DB-not-ML boundary — reports the
 * **SEM-implied** total effect for a parameterized model (arithmetic over the
 * given coefficients), or a note that a numeric estimate must be supplied
 * externally for a structural-only model. No data, no fitting.
 */
final class EstimateEvaluator {

    private EstimateEvaluator() {}

    static QueryResult evaluate(CausalModel model, EstimateStmt s) {
        String t = s.treatment();
        String o = s.outcome();

        // Structural validation: conditioning on a descendant of treatment opens collider bias.
        Set<String> descTreatment = model.descendants(t);
        List<String> invalid = s.adjustFor().stream()
            .filter(descTreatment::contains)
            .collect(Collectors.toList());
        if (!invalid.isEmpty()) {
            return new TextResult("ERROR: adjustment set contains descendants of " + t + ": " + invalid
                + ". Conditioning on descendants of treatment opens collider bias.");
        }

        String estimator = s.estimator() != null ? s.estimator() : "regression";

        if (model.isParameterized()) {
            double effect = new LinearGaussianSEM(model).totalEffect(t, o);
            return new EstimateResult(t, o, s.adjustFor(), estimator, effect,
                "SEM-implied total effect from the declared coefficients.");
        }

        return new EstimateResult(t, o, s.adjustFor(), estimator, null,
            "No declared parameters — a numeric estimate must be supplied externally. "
            + "Fitting from data is out of scope (this is a DB extension, not an ML system); "
            + "parameters are given as input via WEIGHT/MEAN/NOISE.");
    }
}
