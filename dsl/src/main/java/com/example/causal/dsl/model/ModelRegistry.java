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
    private String currentModelName;

    public void registerModel(CausalModel model) {
        models.put(model.name(), model);
        currentModelName = model.name();
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
