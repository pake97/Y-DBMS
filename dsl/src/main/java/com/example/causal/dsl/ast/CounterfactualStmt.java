package com.example.causal.dsl.ast;

import java.util.List;

/**
 * COUNTERFACTUAL (FOR <unit>('<id>'))?
 * (GIVEN <conditions>)?
 * WHAT WOULD <outcome> HAVE BEEN IF <var> = <value>
 */
public record CounterfactualStmt(
    String unit,
    String unitId,
    List<Assignment> given,
    String outcome,
    Assignment ifAssignment
) implements Statement {}
