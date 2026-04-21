package com.example.causal.dsl.ast;

import com.example.causal.dsl.model.VariableRole;
import com.example.causal.dsl.model.VariableType;

/** Variable declaration — used both standalone and inside a DEFINE MODEL block. */
public record VariableDecl(
    String name,
    String unit,
    ObservationBinding binding,
    VariableType type,
    VariableRole role
) {}
