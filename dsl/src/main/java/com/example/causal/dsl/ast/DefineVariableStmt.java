package com.example.causal.dsl.ast;

import com.example.causal.dsl.model.VariableRole;
import com.example.causal.dsl.model.VariableType;

/** DEFINE CAUSAL VARIABLE <name> FOR UNIT <unit> OBSERVED AS "..." TYPE <t> ROLE <r> */
public record DefineVariableStmt(
    String name,
    String unit,
    ObservationBinding binding,
    VariableType type,
    VariableRole role
) implements Statement {}
