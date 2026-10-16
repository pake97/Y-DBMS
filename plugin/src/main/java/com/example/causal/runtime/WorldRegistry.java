package com.example.causal.runtime;

import com.example.causal.api.CausalGraphRuntime;
import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.compile.CompiledCausalGraph;
import com.example.causal.runtime.compile.SnapshotPatcher;

import java.util.*;
import java.util.function.Supplier;

/**
 * Named causal models over the stored graph. The world {@value #BASE} is the compiled stored
 * graph (the base runtime's snapshot). A derived world is a definition, {@code do(X)} on
 * another world, plus a cached snapshot: the base snapshot without the incoming edges of the
 * union of intervened sets down to {@value #BASE}, computed by
 * {@link SnapshotPatcher#cutIncomingEdges}, which shares every untouched closure row with
 * the base.
 *
 * <p>A derived world never takes edits of its own: writes go to the stored graph, which is
 * the base. When the base snapshot changes, every cached derived snapshot is dropped
 * ({@link #baseChanged}) and recomputed from its definition on the next read; a cache entry
 * also remembers the base snapshot it was derived from, so a read never returns a world
 * derived from a stale base.
 *
 * <p>The active world is process-wide, like {@link RuntimeRegistry} itself (see its
 * single-database note). World definitions are not persisted across restarts.
 */
public final class WorldRegistry {

    public static final String BASE = "base";

    private final Supplier<CausalGraphRuntime> baseRuntime;
    private final Map<String, World> worlds = new LinkedHashMap<>();   // guarded by this
    private volatile String active = BASE;

    WorldRegistry(Supplier<CausalGraphRuntime> baseRuntime) {
        this.baseRuntime = baseRuntime;
    }

    /** What intervene measured when it computed a derived snapshot. */
    public record Derivation(int removedEdges, int coneSize, int sharedRows, int totalRows,
                             long millis, boolean fallback) {}

    /** One row of {@link #list()}. {@code intervened} is the resolved set, down to {@value #BASE}. */
    public record Info(String name, String base, List<String> intervened, boolean active,
                       boolean materialized, int nodes, int edges) {}

    private static final class World {
        final String name, from;
        final Set<String> intervened;
        volatile Cached cached;

        World(String name, String from, Set<String> intervened) {
            this.name = name;
            this.from = from;
            this.intervened = intervened;
        }
    }

    private record Cached(CompiledCausalGraph base, CompiledCausalGraph snapshot, Derivation derivation) {}

    // ── definitions ──────────────────────────────────────────────────────────

    /**
     * Define (or replace) {@code name} as {@code do(variables)} on world {@code from} (the
     * active world when blank). Returns the resolved intervened set.
     */
    public synchronized List<String> define(String name, String from, Set<VariableRef> variables) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("world name must not be blank");
        if (BASE.equals(name)) throw new IllegalArgumentException("`" + BASE + "` cannot be redefined");
        String source = from == null || from.isBlank() ? active : from;
        requireKnown(source);
        for (String s = source; !BASE.equals(s); s = worlds.get(s).from) {
            if (s.equals(name)) {
                throw new IllegalArgumentException("`" + name + "` cannot be defined from itself (via `" + source + "`)");
            }
        }
        Set<String> ids = new TreeSet<>();
        variables.forEach(v -> ids.add(v.id()));
        worlds.put(name, new World(name, source, Collections.unmodifiableSet(ids)));
        worlds.values().forEach(w -> w.cached = null);   // dependents of a replaced world
        return resolved(name);
    }

    /** Drop {@code name}; refused for {@value #BASE} and for a world others are defined from. */
    public synchronized void drop(String name) {
        if (BASE.equals(name)) throw new IllegalArgumentException("`" + BASE + "` cannot be dropped");
        requireKnown(name);
        List<String> dependents = worlds.values().stream().filter(w -> w.from.equals(name)).map(w -> w.name).toList();
        if (!dependents.isEmpty()) {
            throw new IllegalArgumentException("`" + name + "` is the base of " + dependents + "; drop those first");
        }
        worlds.remove(name);
        if (name.equals(active)) active = BASE;
    }

    /** Make {@code name} the active world (blank queries read it). */
    public synchronized void use(String name) {
        requireKnown(name);
        active = name;
    }

    public String active() {
        return active;
    }

    /** {@value #BASE} first, then the derived worlds in definition order. */
    public synchronized List<Info> list() {
        List<Info> out = new ArrayList<>();
        CompiledCausalGraph base = baseSnapshotOrNull();
        out.add(new Info(BASE, null, List.of(), BASE.equals(active), base != null,
                base == null ? 0 : base.liveCount(), base == null ? 0 : edges(base)));
        for (World w : worlds.values()) {
            Cached c = w.cached;
            boolean fresh = c != null && c.base == base;
            out.add(new Info(w.name, w.from, resolved(w.name), w.name.equals(active), fresh,
                    fresh ? c.snapshot.liveCount() : 0, fresh ? edges(c.snapshot) : 0));
        }
        return out;
    }

    /** The resolved intervened set of {@code name}: its own plus those of its bases. */
    public synchronized List<String> resolved(String name) {
        requireKnown(name);
        Set<String> ids = new TreeSet<>();
        for (String s = name; !BASE.equals(s); s = worlds.get(s).from) ids.addAll(worlds.get(s).intervened);
        return List.copyOf(ids);
    }

    // ── snapshots ────────────────────────────────────────────────────────────

    /** Blank means the active world. Throws for an unknown name. */
    public String resolveName(String name) {
        String n = name == null || name.isBlank() ? active : name;
        synchronized (this) {
            requireKnown(n);
        }
        return n;
    }

    /** The snapshot of {@code name} (blank: the active world), derived lazily from the base. */
    public CompiledCausalGraph snapshot(String name) {
        String n = resolveName(name);
        CompiledCausalGraph base = baseSnapshot();
        if (BASE.equals(n)) return base;
        return cached(n, base).snapshot;
    }

    /**
     * The runtime that answers queries on {@code name} (blank: the active world): the base
     * runtime itself for {@value #BASE}, else a read-only {@link DefaultCausalGraphRuntime#view}
     * over the derived snapshot.
     */
    public CausalGraphRuntime runtime(String name) {
        String n = resolveName(name);
        return BASE.equals(n) ? baseRuntime.get() : DefaultCausalGraphRuntime.view(snapshot(n));
    }

    /** How {@code name}'s current snapshot was derived (computing it if needed). */
    public Derivation derivation(String name) {
        String n = resolveName(name);
        if (BASE.equals(n)) throw new IllegalArgumentException("`" + BASE + "` is not derived");
        return cached(n, baseSnapshot()).derivation;
    }

    private Cached cached(String name, CompiledCausalGraph base) {
        World w;
        Set<String> intervened;
        synchronized (this) {
            w = worlds.get(name);
            intervened = new TreeSet<>(resolved(name));
        }
        Cached c = w.cached;
        if (c != null && c.base == base) return c;
        long start = System.nanoTime();
        SnapshotPatcher.Surgery s = SnapshotPatcher.cutIncomingEdges(base, intervened);
        long millis = (System.nanoTime() - start) / 1_000_000;
        int total = 0, shared = 0;
        CompiledCausalGraph g = s.graph();
        if (g.hasClosures() && base.hasClosures() && !s.fallback()) {
            for (int i = 0; i < base.size(); i++) {
                if (base.variableAt(i) == null) continue;
                total += 2;
                if (g.ancestorBits(i) == base.ancestorBits(i)) shared++;
                if (g.descendantBits(i) == base.descendantBits(i)) shared++;
            }
        } else {
            total = 2 * base.liveCount();
        }
        c = new Cached(base, g, new Derivation(s.removedEdges(), total - shared, shared, total, millis, s.fallback()));
        w.cached = c;
        return c;
    }

    /** Drop every cached derived snapshot (the base snapshot changed). */
    public void baseChanged() {
        synchronized (this) {
            worlds.values().forEach(w -> w.cached = null);
        }
    }

    private CompiledCausalGraph baseSnapshot() {
        CompiledCausalGraph g = baseSnapshotOrNull();
        if (g == null) throw new IllegalStateException("No compiled snapshot available. Call CALL causal.compile() first.");
        return g;
    }

    private CompiledCausalGraph baseSnapshotOrNull() {
        GraphSnapshot s = baseRuntime.get().currentSnapshot();
        return s instanceof CompiledCausalGraph g ? g : null;
    }

    private void requireKnown(String name) {
        if (!BASE.equals(name) && !worlds.containsKey(name)) {
            throw new IllegalArgumentException("Unknown model: `" + name + "`; causal.models() lists the defined ones");
        }
    }

    private static int edges(CompiledCausalGraph g) {
        int m = 0;
        for (int i = 0; i < g.size(); i++) m += g.childCount(i);
        return m;
    }
}
