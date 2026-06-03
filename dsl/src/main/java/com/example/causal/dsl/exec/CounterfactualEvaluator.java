package com.example.causal.dsl.exec;

import com.example.causal.dsl.ast.Assignment;
import com.example.causal.dsl.ast.CounterfactualStmt;
import com.example.causal.dsl.model.CausalModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates {@code COUNTERFACTUAL} against a {@link CausalModel}. Closed-form for
 * a linear-Gaussian model (see {@link CounterfactualResult}); a structural-only
 * model yields an explanatory note instead. Pure — no I/O, no registry mutation
 * (a counterfactual is a question, not a context switch like {@code INTERVENE}).
 */
final class CounterfactualEvaluator {

    private CounterfactualEvaluator() {}

    static CounterfactualResult evaluate(CausalModel model, CounterfactualStmt s) {
        String x = s.ifAssignment().variable();
        String xStarText = s.ifAssignment().value();
        String y = s.outcome();

        if (!model.isParameterized()) {
            return note(y, x, xStarText, null,
                "Counterfactual requires linear-Gaussian parameters (declare WEIGHT/MEAN/NOISE); "
                + "not available for a structural-only model.");
        }

        Map<String, Double> given = numericGiven(s);
        Double xFactual = given.get(x);
        Double yFactual = given.get(y);
        Double xStar = tryParse(xStarText);

        if (xFactual == null || yFactual == null || xStar == null) {
            return note(y, x, xStarText, yFactual,
                "Insufficient evidence: GIVEN must include numeric factual values for "
                + x + " and " + y + ".");
        }

        // Linear-Gaussian unit counterfactual: hold noise fixed, vary only X.
        double effect = new LinearGaussianSEM(model).totalEffect(x, y);
        double yCf = yFactual + effect * (xStar - xFactual);
        return new CounterfactualResult(y, x, xStarText, yFactual, yCf, yCf - yFactual, null);
    }

    private static CounterfactualResult note(String y, String x, String xStar, Double factual, String msg) {
        return new CounterfactualResult(y, x, xStar, factual, null, null, msg);
    }

    /** Factual values from the GIVEN clause that parse as numbers. */
    private static Map<String, Double> numericGiven(CounterfactualStmt s) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Assignment a : s.given()) {
            Double v = tryParse(a.value());
            if (v != null) out.put(a.variable(), v);
        }
        return out;
    }

    private static Double tryParse(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
