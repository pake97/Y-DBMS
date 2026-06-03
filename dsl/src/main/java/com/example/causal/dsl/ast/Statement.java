package com.example.causal.dsl.ast;

/**
 * Sealed base for every DSL statement. Switch on the concrete type to dispatch.
 */
public sealed interface Statement permits
    DefineVariableStmt,
    DefineModelStmt,
    DefineDagStmt,
    CausesOfStmt,
    EffectsOfStmt,
    AncestorsOfStmt,
    DescendantsOfStmt,
    MediatorsBetweenStmt,
    CollidersBetweenStmt,
    ConfoundersOfStmt,
    BackdoorPathsStmt,
    IdentifyStmt,
    EstimateStmt,
    InterveneStmt,
    CounterfactualStmt,
    UseModelStmt,
    ResetStmt,
    ShowModelsStmt,
    ShowDagStmt,
    DescribeVariableStmt {}
