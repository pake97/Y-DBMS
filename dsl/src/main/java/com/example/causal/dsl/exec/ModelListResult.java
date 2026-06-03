package com.example.causal.dsl.exec;

import com.example.causal.dsl.model.ModelRegistry;

import java.util.List;

/**
 * Result of {@code SHOW MODELS}: every registered model with markers for the
 * active graph and for derived intervention worlds.
 *
 * @param models      one entry per registered model, in registration order
 * @param activeModel name of the active graph, or null when none/​live-backed
 */
public record ModelListResult(List<Entry> models, String activeModel) implements QueryResult {

    /** A registered model: its name, whether it is the active graph, whether it is a derived world. */
    public record Entry(String name, boolean active, boolean derived) {}

    /** Snapshot the registry's models into a result (shared by both executors). */
    public static ModelListResult from(ModelRegistry registry) {
        String active = registry.currentModelName().orElse(null);
        List<Entry> entries = registry.allModels().keySet().stream()
            .map(name -> new Entry(name, name.equals(active), registry.isDerived(name)))
            .toList();
        return new ModelListResult(entries, active);
    }

    @Override
    public String toString() {
        if (models.isEmpty()) {
            return "models: (none registered)"
                + (activeModel != null ? " — active: " + activeModel : "");
        }
        StringBuilder sb = new StringBuilder("models:");
        for (Entry e : models) {
            sb.append("\n  ").append(e.active() ? "* " : "  ").append(e.name());
            if (e.derived()) sb.append("  (derived)");
        }
        return sb.toString();
    }
}
