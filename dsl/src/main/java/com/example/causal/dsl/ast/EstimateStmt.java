package com.example.causal.dsl.ast;

import java.util.List;

/**
 * ESTIMATE EFFECT OF <treatment> ON <outcome>
 * (ADJUST FOR [vars])?
 * (USING <estimator>)?
 */
public record EstimateStmt(
    String treatment,
    String outcome,
    List<String> adjustFor,
    String estimator
) implements Statement {}
