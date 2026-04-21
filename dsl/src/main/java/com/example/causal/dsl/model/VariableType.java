package com.example.causal.dsl.model;

public enum VariableType {
    BINARY, CONTINUOUS, CATEGORICAL, EVENT, LATENT;

    public boolean isObserved() {
        return this != LATENT;
    }
}
