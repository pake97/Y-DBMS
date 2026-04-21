package com.example.causal.dsl.ast;

/** A variable assignment used in INTERVENE ON and COUNTERFACTUAL IF clauses. */
public record Assignment(String variable, String value) {}
