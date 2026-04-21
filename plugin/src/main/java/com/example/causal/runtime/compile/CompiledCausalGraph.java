package com.example.causal.runtime.compile;

import com.example.causal.api.GraphSnapshot;
import com.example.causal.api.VariableRef;
import com.example.causal.runtime.model.Variable;

import java.time.Instant;
import java.util.*;

/**
 * Immutable compiled representation of the causal DAG.
 *
 * <p>This is the central data structure that Student A is responsible for
 * optimising. The current implementation uses plain Java collections.
 * It must be replaced with dense integer-indexed arrays for performance at scale.
 *
 * <h2>Student A implementation checklist</h2>
 * <ol>
 *   <li>Replace {@code variableById} + {@code forwardAdj} maps with a dense
 *       {@code int}-indexed layout:
 *       <pre>
 *         Map&lt;String, Integer&gt; nameToIndex;   // variable id → dense int index
 *         Variable[]           indexToVar;     // dense index → Variable
 *         int[][]              forwardAdj;     // index → int[] of child indices
 *         int[][]              reverseAdj;     // index → int[] of parent indices
 *         int[]                topoOrder;      // topological order as int indices
 *       </pre>
 *   </li>
 *   <li>Pre-compute ancestor and descendant bitsets per variable:
 *       <pre>
 *         long[][] ancestorBits;    // ancestorBits[i] is a bitset of ancestors of i
 *         long[][] descendantBits;  // descendantBits[i] is a bitset of descendants of i
 *       </pre>
 *       Process in topological order so ancestors[i] = union of ancestors[parent] ∪ {parent}.
 *   </li>
 *   <li>Expose {@code topoIndex(String id)} as an O(1) map lookup.</li>
 *   <li>Pass the dense arrays to {@link com.example.causal.runtime.algorithms.ReachabilityIndex}
 *       so that d-separation can use O(n/64) bitset intersection.</li>
 * </ol>
 */
public final class CompiledCausalGraph implements GraphSnapshot {

    private final Instant compiledAt;
    private final Map<String, Variable> variableById;      // TODO(student): → dense int[] layout
    private final Map<String, Set<String>> forwardAdj;     // TODO(student): → int[][] forwardAdj
    private final Map<String, Set<String>> reverseAdj;     // TODO(student): → int[][] reverseAdj
    private final List<String> topoOrder;                  // TODO(student): → int[] topoOrder

    // TODO(student): add when bitset cache is implemented
    // private final long[][] ancestorBits;
    // private final long[][] descendantBits;

    CompiledCausalGraph(
            Map<String, Variable> variableById,
            Map<String, Set<String>> forwardAdj,
            Map<String, Set<String>> reverseAdj,
            List<String> topoOrder,
            Instant compiledAt
    ) {
        this.variableById = Collections.unmodifiableMap(new LinkedHashMap<>(variableById));
        this.forwardAdj   = Collections.unmodifiableMap(deepCopy(forwardAdj));
        this.reverseAdj   = Collections.unmodifiableMap(deepCopy(reverseAdj));
        this.topoOrder    = Collections.unmodifiableList(new ArrayList<>(topoOrder));
        this.compiledAt   = compiledAt;
    }

    // -------------------------------------------------------------------------
    // GraphSnapshot
    // -------------------------------------------------------------------------

    @Override
    public Set<VariableRef> variables() {
        Set<VariableRef> out = new LinkedHashSet<>();
        for (String id : variableById.keySet()) out.add(new VariableRef(id));
        return Collections.unmodifiableSet(out);
    }

    @Override public int size()                    { return variableById.size(); }
    @Override public boolean contains(VariableRef r) { return variableById.containsKey(r.id()); }
    @Override public Instant compiledAt()          { return compiledAt; }

    // -------------------------------------------------------------------------
    // Internal accessors — used by DefaultCausalGraphRuntime and algorithms
    // -------------------------------------------------------------------------

    /** Look up the internal Variable record by id. */
    public Optional<Variable> findVariable(String id) {
        return Optional.ofNullable(variableById.get(id));
    }

    /** Direct parents (causes) of the variable with the given id. */
    public Set<String> parentsOf(String id) {
        return reverseAdj.getOrDefault(id, Set.of());
    }

    /** Direct children (effects) of the variable with the given id. */
    public Set<String> childrenOf(String id) {
        return forwardAdj.getOrDefault(id, Set.of());
    }

    /** Topological order of variable ids, sources first. */
    public List<String> topoOrder() {
        return topoOrder;
    }

    /**
     * Topological position of the variable (0 = source), or -1 if unknown.
     * TODO(student): replace with O(1) map lookup once dense layout is in place.
     */
    public int topoIndex(String id) {
        return topoOrder.indexOf(id);
    }

    // -------------------------------------------------------------------------

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return copy;
    }
}
