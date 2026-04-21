package com.example.causal.api;

/**
 * Result of a d-separation query (X ⊥ Y | Z).
 *
 * <p>{@code separated = true} means X and Y are d-separated given Z,
 * implying conditional independence in every model faithful to the DAG.
 *
 * <p>The {@code explanation} field is intended for human-readable debugging
 * (e.g. which path was found open). It is not part of the semantic contract.
 */
public record DSeparationResult(boolean separated, String explanation) {

    public static DSeparationResult separated(String explanation) {
        return new DSeparationResult(true, explanation);
    }

    public static DSeparationResult connected(String explanation) {
        return new DSeparationResult(false, explanation);
    }

    /** Placeholder returned while the algorithm is not yet implemented. */
    public static DSeparationResult unimplemented() {
        return new DSeparationResult(false, "TODO: d-separation algorithm not yet implemented");
    }
}
