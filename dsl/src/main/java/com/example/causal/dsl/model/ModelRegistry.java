package com.example.causal.dsl.model;

import java.util.*;

/**
 * Session-scoped store of named CausalModels and standalone variable definitions.
 * Standalone variables (defined outside any model block) accumulate here until
 * a DEFINE CAUSAL DAG statement attaches them to a new model.
 */
public class ModelRegistry {

    private final Map<String, CausalModel> models = new LinkedHashMap<>();
    private final Map<String, CausalVariable> standaloneVariables = new LinkedHashMap<>();
    private final Set<String> derivedModels = new LinkedHashSet<>();
    private String currentModelName;
    private String baseModelName;

    /** Register a base (non-derived) model and make it the active graph. */
    public void registerModel(CausalModel model) {
        models.put(model.name(), model);
        derivedModels.remove(model.name());
        currentModelName = model.name();
        baseModelName = model.name();
    }

    /**
     * Register a derived intervention world (a graph produced by the do-operator)
     * and make it the active graph. The base model is left as the reset target.
     */
    public void registerDerived(CausalModel model) {
        models.put(model.name(), model);
        derivedModels.add(model.name());
        currentModelName = model.name();
    }

    /** True if the active graph is a derived intervention world (not the base). */
    public boolean isCurrentDerived() {
        return currentModelName != null && derivedModels.contains(currentModelName);
    }

    /** True if the named model is a derived intervention world. */
    public boolean isDerived(String name) {
        return derivedModels.contains(name);
    }

    /** Name of the active graph, or empty when none is set. */
    public Optional<String> currentModelName() {
        return Optional.ofNullable(currentModelName);
    }

    /** Switch the active graph to a registered model (base or derived). */
    public void useModel(String name) {
        if (!models.containsKey(name)) {
            throw new IllegalArgumentException("Unknown model: " + name);
        }
        currentModelName = name;
    }

    /** Reset the active graph back to the base (original) model. */
    public void reset() {
        currentModelName = baseModelName;
    }

    public void registerStandaloneVariable(CausalVariable v) {
        standaloneVariables.put(v.name(), v);
    }

    public Optional<CausalModel> model(String name) {
        return Optional.ofNullable(models.get(name));
    }

    public Optional<CausalModel> currentModel() {
        return currentModelName != null ? model(currentModelName) : Optional.empty();
    }

    public Map<String, CausalVariable> standaloneVariables() {
        return Collections.unmodifiableMap(standaloneVariables);
    }

    public Map<String, CausalModel> allModels() {
        return Collections.unmodifiableMap(models);
    }

    /**
     * Look up a variable by name: first in the current model, then in standalone variables.
     */
    public Optional<CausalVariable> resolveVariable(String name) {
        return currentModel()
            .flatMap(m -> m.variable(name))
            .or(() -> Optional.ofNullable(standaloneVariables.get(name)));
    }
}
