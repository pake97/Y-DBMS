package com.example.causal.dsl.ast;

/**
 * SHOW MODELS
 *
 * <p>List every registered model — base models and derived intervention worlds —
 * marking which one is the active graph.
 */
public record ShowModelsStmt() implements Statement {}
