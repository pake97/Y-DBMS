package com.example.causal.dsl.ast;

/**
 * USE MODEL &lt;name&gt;
 *
 * <p>Switch the active graph to a named model — either a base model or a derived
 * intervention world produced by {@link InterveneStmt} (e.g.
 * {@code UserRetentionModel_do_Treatment_1}).
 */
public record UseModelStmt(String modelName) implements Statement {}
