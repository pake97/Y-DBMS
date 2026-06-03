package com.example.causal.dsl.ast;

/**
 * RESET
 *
 * <p>Reset the active graph back to the base (original) model, discarding the
 * active intervention world. Derived worlds remain registered and can be
 * re-selected with {@link UseModelStmt}.
 */
public record ResetStmt() implements Statement {}
