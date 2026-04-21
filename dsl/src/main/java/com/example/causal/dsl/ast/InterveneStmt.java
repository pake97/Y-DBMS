package com.example.causal.dsl.ast;

/**
 * (FROM MODEL <model>)?
 * INTERVENE ON <var> = <value>
 * (COMPARE TO <var> = <value>)?
 * RETURN DISTRIBUTION|EFFECT|EXPECTATION OF <var>
 */
public record InterveneStmt(
    String modelRef,
    Assignment intervention,
    Assignment compareTo,
    String returnType,
    String returnVar
) implements Statement {}
