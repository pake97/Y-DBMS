package com.example.causal.dsl.ast;

/**
 * IDENTIFY EFFECT OF <treatment> ON <outcome>
 * (RETURN ADJUSTMENT SETS)?
 * EXPLAIN?
 */
public record IdentifyStmt(
    String treatment,
    String outcome,
    boolean returnAdjustmentSets,
    boolean explain
) implements Statement {}
