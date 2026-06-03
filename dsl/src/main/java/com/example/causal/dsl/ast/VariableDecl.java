package com.example.causal.dsl.ast;

import com.example.causal.dsl.model.VariableRole;
import com.example.causal.dsl.model.VariableType;

/**
 * Variable declaration — used both standalone and inside a DEFINE MODEL block.
 *
 * <p>{@code mean} (intercept μ₀) and {@code noise} (variance σ²) are the
 * linear-Gaussian parameters from optional {@code MEAN}/{@code NOISE} clauses;
 * null when undeclared (the SEM defaults to intercept 0.0, noise variance 1.0).
 */
public record VariableDecl(
    String name,
    String unit,
    ObservationBinding binding,
    VariableType type,
    VariableRole role,
    Double mean,
    Double noise
) {}
