# Causal4j — ICDE 2027 Regular Research Paper: Working Outline

**Target:** ICDE 2027, Research track, **Regular research paper** (≤12 pages excl. refs, IEEE format, single-blind).
**Realistic deadline:** **Round 2 — Nov 11, 2026** (Round 1, June 11 2026, has passed). Notification Feb 10 2027; camera-ready Mar 10 2027.
**Artifact:** public GitHub repo required-ish (availability is scored; strongly recommended).

Working title (pick one):
- *Causal4j: A Declarative Language and In-DBMS Index for Causal Structural Queries over Graph Databases*
- *Bringing Causal Queries into the Graph DBMS: A Query Language and Reachability Index for Causal DAGs*

---

## 0. The single most important framing decision (read first)

ICDE **desk-rejects** work judged "purely causal inference / ML, not data engineering." Our defense is that this is a **data-systems** paper, not a causal-inference paper. Everything is framed as:

- a **query language** (Data models, semantics, and query languages),
- an **index + physical design** inside a DBMS (Storage, indexing, and physical database design),
- **query processing & optimization** over **graph data**,
- with **incremental view/index maintenance** under updates.

The structural-causal-model math (linear-Gaussian SEM, do-operator, counterfactuals) is presented as the **semantics the engine supports**, never as the contribution. Reviewers must never think "this belongs at UAI/CLeaR."

**Draft scope justification (the 1000-char box on the submission form):**
> We address data-engineering challenges in supporting causal analysis as a first-class workload inside a graph DBMS: (a) a declarative query language whose operators (ancestors, d-separation, adjustment-set enumeration, backdoor paths) compile to physical operators over a graph store; (b) a compiled in-memory index (dense CSR adjacency + topological-layer pruning + ancestor/descendant bitsets) that turns per-query O(V+E) DAG traversals into O(V/64) bitset operations; (c) incremental index maintenance under graph updates. We evaluate throughput, latency, index build time, and memory vs. traversal and library baselines. Builds on graph reachability indexing and query-processing work published at ICDE/VLDB/SIGMOD (§2, §9).

---

## 1. Contributions (state these verbatim in the intro, 4 bullets)

1. **A declarative causal query language** embedded in a graph DBMS (Cypher-compatible), covering structural queries, identification (adjustment sets, backdoor), and a quantitative layer (interventions, counterfactuals) with a defined compilation to physical operators. *(grammar: `CausalDSL.g4`; translator: `CypherTranslator`)*
2. **A compiled causal index** (`CompiledCausalGraph`): dense integer-indexed CSR adjacency + topological order/layer + precomputed ancestor/descendant **bitsets**, reducing structural queries from DFS O(V+E) to O(V/64) word operations, and enabling bitset Bayes-Ball d-separation and adjustment-set enumeration.
3. **Incremental maintenance** of the index under `CAUSES`/`Variable` mutations (edge insert/delete), avoiding full recompilation. *(hooks: `lifecycle/CausalGraphChangeListener`, `ChangeBatch`)*
4. **An empirical study** on standard Bayesian-network DAGs + synthetic scale-free DAGs, vs. (i) naive in-DBMS Cypher traversal, (ii) Neo4j GDS, (iii) external Python causal libraries, reporting latency/throughput/build-time/memory + a reproducible artifact.

> **Reality check (very direct):** Contributions **2, 3, 4 are not built yet.** Today `CompiledCausalGraph`/`ReachabilityIndex` use `Map` adjacency + iterative DFS (Tier-2 bitset work is all `TODO`); there is **no incremental path executed** end-to-end; there is **no benchmark harness anywhere** in the repo. The language (1) and structural correctness are real. See §12 for the build plan. Without 2–4 this is not an ICDE regular paper.

---

## 2. Introduction (1.25 pp)

- Hook: causal questions ("what confounds T→Y? what's a valid adjustment set? is X ⊥ Y | Z?") are increasingly asked over data that already lives in a **graph database**, but today analysts export the DAG to a Python library, losing DBMS benefits (persistence, concurrency, incremental updates, declarative querying, integration with the rest of the graph).
- Problem: no DBMS treats causal structural queries as a first-class, indexed workload. Naive Cypher variable-length paths are expressive but slow and can't express d-separation/adjustment directly.
- Our approach in one figure (Fig. 1 architecture).
- Contributions (the 4 above).
- Results preview (fill after eval): "Causal4j answers d-separation/adjustment queries N× faster than Cypher traversal and M× faster than round-tripping to DoWhy, with sub-second index builds on DAGs up to K nodes."

## 3. Background & Problem Statement (1 p)

- Causal DAGs; observed/latent variables; the `(:Variable)-[:CAUSES{active}]->(:Variable)` schema.
- The query catalog and what each means (parents/children, ancestors/descendants, mediators, colliders, confounders, backdoor variables, d-separation, adjustment sets). Keep definitions tight; cite Pearl, Peters et al., Textbook of causal inference.
- Why in-DBMS: updates, persistence, mixed workloads, declarative access. **Frame as the data-engineering gap.**
- Running example: the **Piccininni et al. (2020) COVID-19 DAG** (25 variables, 76 edges) from `dev/seed-dag.cypher`. Use it in Fig. 2 and throughout.

## 4. The Causal Query Language (1.5 pp)

- Design goals: declarative, close to how epidemiologists phrase questions, compiles to graph operators.
- Grammar tour (subset of `CausalDSL.g4`): `DEFINE MODEL / DEFINE CAUSAL DAG`, structural queries, `IDENTIFY EFFECT OF t ON y RETURN ADJUSTMENT SETS`, `ESTIMATE`, `INTERVENE ... COMPARE TO`, `COUNTERFACTUAL ... WHAT WOULD y HAVE BEEN IF`.
- **Table 1: language operators → semantics → physical operator/procedure** (`causal.ancestors`, `causal.dsep`, `causal.adjustmentCandidate`, ...). This table is the bridge that makes the paper "query languages + query processing," not causal theory.
- Compilation: AST → Cypher writes for schema maintenance + `CALL causal.*` for reads (`CypherTranslator`). Two execution backends (in-memory vs. live Neo4j) as an implementation note.
- Semantics: give the denotational definition of each structural operator over the DAG (short, formal). Model registry / derived intervention worlds (`USE MODEL`, `RESET`) as a "world" abstraction.

## 5. Compilation: The Causal Snapshot (0.75 p)

- `GraphLoader` reads only `active=true` `CAUSES`; validates acyclicity (topological sort, `TopologicalSorter`); builds the immutable `CompiledCausalGraph`.
- Why a snapshot outside the per-call procedure instances (`RuntimeRegistry` singleton); consistency model; when it's rebuilt vs. maintained.

## 6. Indexing & Query Processing — **the core section** (2.5–3 pp)

This is where the paper earns ICDE. Present the index as physical design + the query algorithms that exploit it.

- **Physical layout.** Dense `nameToIndex` (id→int), `indexToVar`, CSR `forwardAdj`/`reverseAdj` as `int[][]`, `topoOrder` as `int[]`, `topoLayer` for traversal pruning. Contrast with the map-of-sets baseline (pointer chasing, boxing).
- **Reachability bitsets.** `ancestorBits`/`descendantBits` = `long[⌈n/64⌉]` per node, filled in (reverse-)topological order: `ancestorBits[i] = ⋃_p (ancestorBits[p] | bit(p))`. Build cost O(V·(V+E)/64) time, O(V²/64) space; query O(V/64).
- **Algorithms over the index:**
  - ancestors/descendants/hasDirectedPath → single bitset read/test.
  - **d-separation via bitset Bayes-Ball** (`causal.dsep`): conditioning set + reachable set as bitsets, O(V/64) intersections.
  - **adjustment-set enumeration** (`causal.adjustmentCandidate`): backdoor criterion using forbidden set = descendants(T) on directed T→Y paths; enumerate minimal candidates in deterministic topo order.
  - mediators/colliders/confounders/backdoor variables (`PathAnalyzer`) recast as bitset intersections of ancestor/descendant sets.
- **Table 2: per-operator complexity — map+DFS baseline vs. bitset index** (build, query, space). This table is the technical spine.
- **Design trade-offs:** full transitive-closure bitsets (fast query, O(V²/64) memory) vs. topo-layer-pruned on-demand DFS (low memory) — discuss the crossover; this is a legit query-optimization discussion. Optionally a cost-based choice by |V|, density, query mix.

## 7. Incremental Index Maintenance (1 p)

- Update model: edge insert/delete, variable add/remove, `active` toggle (`ChangeBatch`, `CausalGraphChangeListener`).
- Insert edge u→v: reject if creates a cycle (v ⇝ u?); else OR-in `ancestorBits[u]∪{u}` into descendants' ancestor sets and symmetric for descendants — bounded by affected region, not full rebuild. Delete requires recomputation over the affected cone (discuss cost, when to fall back to full recompile).
- **Table/plot:** incremental update latency vs. full recompile, as a function of update batch size. Strong "data engineering" result if it lands.

## 8. Quantitative Layer & Expressiveness (0.75 p — keep short)

- Linear-Gaussian SEM parameters on the same snapshot (edge β, intercept μ₀, noise σ²); `causal.linearGaussianInference`, `causal.counterfactual`; derived intervention worlds.
- **Explicitly framed as expressiveness the indexed engine enables, not a contribution.** One worked `INTERVENE`/`COUNTERFACTUAL` example on the COVID DAG. Do NOT over-invest — reviewers penalize causal-theory bloat.

## 9. Evaluation (2.5 pp) — plan in §12, results here

Research questions:
- **RQ1** Query latency/throughput: bitset index vs. (a) map+DFS internal baseline, (b) plain Cypher variable-length paths, (c) Neo4j GDS reachability, (d) external libs (DoWhy/pgmpy/causal-learn/dagitty via round-trip).
- **RQ2** Index build time + memory vs. graph size/density.
- **RQ3** Incremental maintenance vs. full recompile.
- **RQ4** Scalability: |V| up to 10³–10⁴, varying density; query-mix sensitivity.
- **RQ5** Correctness: agreement with dagitty/causal-learn on d-sep & adjustment sets (validation, not a perf claim).

Datasets:
- Standard BN DAGs from **bnlearn** repository (asia 8, sachs 11, child 20, alarm 37, hepar2 70, win95pts 76, andes 223, pigs 441, munin 1041) — recognized, reproducible.
- **Synthetic scale-free DAGs** (Barabási–Albert / Erdős–Rényi DAGs) to sweep size × density independently.
- COVID DAG as qualitative running example.

Metrics: median/p99 latency, throughput (q/s), build time, resident memory, incremental-update latency. Hardware + JVM + Neo4j version stated. Repeat runs, report variance (EAB-grade rigor helps even for a regular paper).

## 10. Related Work (0.75–1 p)

- **Graph reachability indexing** (2-hop / TF-label / GRAIL / PLL / bitset transitive closure) — position our per-node ancestor/descendant bitsets in this lineage; cite ICDE/VLDB/SIGMOD works. *This is the citation anchor that proves DE relevance.*
- **Query processing over graph DBMS / property graphs / Cypher / GQL.**
- **In-DBMS analytics / UDFs / procedure frameworks.**
- **Causal tooling** (DoWhy, pgmpy, causal-learn, dagitty, Ananke/Y0) — none are DBMS-resident or incrementally maintained; that's our gap.
- Incremental view/index maintenance.

## 11. Conclusion + Future Work (0.25 p)

- Cost-based operator selection; ADMG/latent-projection (c-components) index; multi-query/batched adjustment enumeration; concurrency & MVCC on the snapshot.

## 12. AI-generated content acknowledgement

Required by ICDE/IEEE: disclose any LLM assistance (text/figures/code) in Acknowledgements, name the tool, and identify which sections. (We'll fill this at submission.)

---

## Figures & Tables checklist

- **Fig. 1** System architecture: DSL → AST → CypherTranslator → {writes, `CALL causal.*`} → RuntimeRegistry/CompiledCausalGraph index → Neo4j store.
- **Fig. 2** COVID DAG running example (subgraph highlighting a T→Y query, backdoor path, adjustment set).
- **Fig. 3** Index layout diagram (CSR + bitsets), with the reverse-topo fill.
- **Fig. 4** Latency vs. |V| (log-log), methods overlaid (RQ1/RQ4).
- **Fig. 5** Incremental update vs. full recompile (RQ3).
- **Table 1** Language operator → semantics → physical operator.
- **Table 2** Per-operator complexity: baseline vs. index (build/query/space). **← technical spine**
- **Table 3** Dataset characteristics (|V|, |E|, density, source).
- **Table 4** Memory & build-time by dataset.

---

## 13. Gap / risk plan — what must be built before Nov 11, 2026 (VERY DIRECT)

Ranked by "paper dies without it":

1. **Build the bitset index (Contribution 2).** Implement the Tier-2 dense layout + `ancestorBits`/`descendantBits` in `CompiledCausalGraph`; switch `ReachabilityIndex`, `PathAnalyzer`, d-sep, and adjustment enumeration to read it. ~2–3 wks. **Blocking for §6 and all of §9.**
2. **Benchmark harness + baselines.** JMH (or a careful wall-clock harness) + dataset loaders (bnlearn + synthetic DAG generator) + the map+DFS baseline (keep the current impl as the baseline!) + Cypher-traversal baseline + GDS + a Python round-trip harness (DoWhy/dagitty) for RQ1/RQ5. ~3–4 wks. **Blocking for §9.**
3. **Incremental maintenance end-to-end (Contribution 3).** Wire `CausalGraphChangeListener`/`ChangeBatch` to actually mutate the index; correctness tests vs. full recompile; RQ3 numbers. ~2 wks. If it slips, demote to future work and drop the incremental claim (weakens but survives).
4. **Correctness validation vs. dagitty/causal-learn (RQ5).** Cross-check d-sep + adjustment sets on all datasets. ~few days. Cheap credibility.
5. **Artifact packaging.** Public repo, scripts to reproduce every figure. Availability is scored.

Timeline sketch (from ~mid-Aug): index (Aug), harness+baselines (Sep), incremental + full eval sweeps (Oct), writing/figures in parallel from Sep, buffer + internal reviews late Oct/early Nov.

Things to explicitly NOT do: don't expand the SEM/counterfactual theory; don't add new DSL surface; don't chase ADMG/latent-projection indexing for v1 (future work). Every hour there is an hour not spent on the index/eval that the paper is actually judged on.

Open decisions for Amedeo:
- Author list + ORCIDs (required at submission). "Student A" comments suggest ≥2 authors.
- Any prior publication (workshop/thesis) with >4 pp overlap? Must cite (novelty rule).
- Compute available for the scaling sweep (biggest DAGs / many repeats)?
