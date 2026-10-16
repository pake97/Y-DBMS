package com.example.causal.api;

/**
 * Canonical names of the {@code causal.*} procedures, shared by both sides of
 * the Bolt boundary so they cannot drift:
 * <ul>
 *   <li>the plugin's {@code @Procedure(name = …)} annotations, and</li>
 *   <li>the DSL's {@code CypherTranslator}, which builds {@code CALL …} strings.</li>
 * </ul>
 *
 * <p>These are compile-time constants ({@code public static final String}), so a
 * rename here is a compilation change on every reference — a procedure renamed
 * without updating its caller can no longer compile, which is exactly the class
 * of "no procedure with the name causal.X" runtime failure this prevents.
 *
 * <p>This lives in {@code api} because it is the one plugin package the DSL is
 * permitted to depend on.
 */
public final class ProcedureNames {

    private ProcedureNames() {}

    public static final String COMPILE              = "causal.compile";
    public static final String INVALIDATE           = "causal.invalidate";
    public static final String INSPECT_SNAPSHOT     = "causal.inspectSnapshot";

    // Named models (WP4): derived worlds do(X) over the stored graph
    public static final String INTERVENE            = "causal.intervene";
    public static final String USE                  = "causal.use";
    public static final String MODELS               = "causal.models";
    public static final String DROP                 = "causal.drop";

    public static final String PARENTS              = "causal.parents";
    public static final String CHILDREN             = "causal.children";
    public static final String ANCESTORS            = "causal.ancestors";
    public static final String DESCENDANTS          = "causal.descendants";

    public static final String DSEP                 = "causal.dsep";
    public static final String ADJUSTMENT_CANDIDATE = "causal.adjustmentCandidate";
    public static final String ADJUSTMENT_SETS      = "causal.adjustmentSets";

    public static final String MEDIATORS            = "causal.mediators";
    public static final String COLLIDERS            = "causal.colliders";
    public static final String CONFOUNDERS          = "causal.confounders";
    public static final String BACKDOOR_VARIABLES   = "causal.backdoorVariables";

    // Id-returning variants: one `id` string column, no node lookup.
    public static final String PARENT_IDS            = "causal.parentIds";
    public static final String CHILD_IDS             = "causal.childIds";
    public static final String ANCESTOR_IDS          = "causal.ancestorIds";
    public static final String DESCENDANT_IDS        = "causal.descendantIds";
    public static final String MEDIATOR_IDS          = "causal.mediatorIds";
    public static final String COLLIDER_IDS          = "causal.colliderIds";
    public static final String CONFOUNDER_IDS        = "causal.confounderIds";
    public static final String BACKDOOR_VARIABLE_IDS = "causal.backdoorVariableIds";

    public static final String LINEAR_GAUSSIAN_INFERENCE = "causal.linearGaussianInference";
    public static final String COUNTERFACTUAL       = "causal.counterfactual";
}
