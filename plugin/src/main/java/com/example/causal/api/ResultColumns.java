package com.example.causal.api;

/**
 * Canonical {@code YIELD} column names returned by the {@code causal.*}
 * procedures. A procedure's output columns are the public field names of its
 * DTO row type (Neo4j maps them by reflection), so these constants must equal
 * those field names exactly.
 *
 * <p>The DSL's {@code CypherTranslator} references these when building
 * {@code YIELD …} clauses, so a wrong column name (the {@code YIELD size} class
 * of bug) becomes impossible to type by hand. The plugin side is held to the
 * same names by {@code ProcedureContractTest}, which reflects over the DTOs and
 * asserts their fields match these constants.
 */
public final class ResultColumns {

    private ResultColumns() {}

    // SnapshotRecord — causal.compile / causal.invalidate / causal.inspectSnapshot
    public static final String VARIABLE_COUNT        = "variableCount";
    public static final String VARIABLES             = "variables";
    public static final String COMPILED_AT           = "compiledAt";

    // VariableNodeRecord — parents / children / ancestors / descendants / path queries
    public static final String VARIABLE              = "variable";

    // AdjustmentRecord — causal.adjustmentCandidate
    public static final String ADJUSTMENT_SET_EXISTS = "adjustmentSetExists";
    public static final String CANDIDATE_SETS        = "candidateSets";
    public static final String EXPLANATION           = "explanation";

    // DSeparationRecord — causal.dsep
    public static final String SEPARATED             = "separated";

    // LinearGaussianRecord — causal.linearGaussianInference
    public static final String MEANS                 = "means";
    public static final String VARIANCES             = "variances";
    public static final String TOTAL_EFFECT          = "totalEffect";

    // CounterfactualRecord — causal.counterfactual
    public static final String FACTUAL               = "factual";
    public static final String COUNTERFACTUAL        = "counterfactual";
    public static final String DIFFERENCE            = "difference";
}
