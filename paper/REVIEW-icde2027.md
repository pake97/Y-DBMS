# Paper Review: Causal4j: A Declarative Language and In-DBMS Reachability Index for Causal Structural Queries over Graph Databases

Review of the current draft (`Downloads/Causal4j`, sections 01–10, Sep 26 2026; identical in content to `Desktop/Causal4j/paper`, which additionally has the compiled PDF and `refs.bib`) together with the code in `Desktop/Causal4j` (plugin, dsl, pipeline, dev). Written as an ICDE 2027 research-track reviewer would read it, plus a section on what the code can already support and what to add.

## Paper Metadata

- **Authors**: Amedeo Pachera, Kirollos Nayrouz, Angela Bonifati, Andrea Mauri (CNRS LIRIS, Lyon 1)
- **Venue**: ICDE 2027, Research track, regular paper (target: Round 2, Nov 11 2026; 12 pages excl. references; single-blind)
- **Domain**: Graph data management; query languages; indexing and physical design; incremental maintenance
- **Paper Type**: Systems / query processing
- **State of the draft**: complete prose skeleton for all sections; abstract, results preview, evaluation numbers, both evaluation figures, bibliography (14 of 16 entries are `TODO_*` placeholders) all pending

## Executive Summary

The paper argues that causal structural queries (ancestors, mediators, confounders, backdoor paths, d-separation, adjustment sets) are today answered by exporting a DAG to an external library, and proposes to make them a native, indexed workload inside Neo4j: a small declarative DSL compiling to `causal.*` procedures, an immutable compiled snapshot with a dense CSR layout and precomputed ancestor/descendant bitsets, and incremental maintenance of that index under graph updates. The framing is right for ICDE: the problem is stated as a data-engineering problem (R1–R4), the causal semantics are explicitly positioned as the workload rather than the contribution, and the related-work anchor (reachability indexing) is the correct lineage.

The idea is solid and, in my judgment, publishable at ICDE if three things happen. First, the two contributions the paper is judged on (the bitset index and incremental maintenance) do not exist yet in the code: `CompiledCausalGraph` is a `Map<String,Set<String>>` adjacency, `ReachabilityIndex` is per-query DFS, and `CausalGraphChangeListener.afterCommit` invalidates the snapshot. The draft is honest about this in `\NOTE` blocks, which is good practice internally, but as submitted today the paper would be rejected for claiming unmeasured speedups. Second, the algorithmic story around the index has two weak points a reviewer with a causal-graph background will find immediately: the complexity table claims d-separation is $O(V/64)$, which is not true of Bayes-Ball (it is $O(V+E)$; bitsets only lower the constant), and adjustment-set enumeration is brute-force over $\binom{n}{k}$ candidate sets, while polynomial-delay algorithms (van der Zander, Liśkiewicz and Textor, AIJ 2019) exist and are what dagitty, the paper's own correctness oracle, implements. Third, the paper does not cite the authors' own PVLDB 2025 vision paper ("What If: Causal Analysis with Graph Databases", PVLDB 18(11)), which proposed causal operators for property-graph databases and a Neo4j proof of concept; under ICDE's novelty rule that paper must be cited and the delta stated explicitly.

None of these is fatal. The delta over the vision paper is real (a concrete language, a physical index, incremental maintenance, an evaluation), the reachability-indexing angle is unexplored for this workload, and the authors have a working, tested Bayes-Ball and backdoor implementation to build on. The rest of this review is organized around what to fix and what to add so the paper earns its claims.

## Summary of Contributions (as claimed, §1)

1. A declarative causal query language embedded in a graph DBMS, with a defined compilation to physical operators (§3).
2. A compiled reachability index for causal DAGs (dense CSR, topological layers, ancestor/descendant bitsets) reducing structural queries from $O(V+E)$ to $O(V/64)$ and supporting bitset Bayes-Ball d-separation and adjustment enumeration (§5).
3. An incremental maintenance scheme bounding update work to the affected region (§6).
4. An empirical study on bnlearn DAGs and synthetic scale-free DAGs against in-DBMS Cypher, Neo4j GDS, and external libraries (§8).

### Claims versus evidence

| Claim | Where | Evidence today | Strength |
|---|---|---|---|
| Language compiles one-to-one to `causal.*` procedures | §3, Table 1 | `CypherTranslator.java`, `ProcedureNames.java`, 15 procedures and 2 user functions, e2e tests | Strong |
| Bayes-Ball d-separation and backdoor adjustment are correct | §5.4–5.5, R3 | `DefaultCausalGraphRuntime.java`, `DSeparationBayesBallTest`, `AdjustmentCandidateTest`, `DsepAndAdjustmentE2ETest` | Moderate (no cross-tool validation yet) |
| Bitset index gives $O(V/64)$ structural queries | §5.1–5.3, Table 2 | Not implemented (`TODO(student, tier 2)` throughout) | None |
| d-separation is $O(V/64)$ with bitsets | §5.4, Table 2 | Incorrect as stated; Bayes-Ball is $O(V+E)$ | Weak (claim needs rewriting) |
| Adjustment enumeration scales because the inner test is fast | §5.5 | Outer loop enumerates $\binom{n}{k}$ subsets (`combinationsOfSize`) | Weak |
| Incremental patch bounded by the affected cone | §6 | Listener and `ChangeBatch` diff exist; `afterCommit` calls `invalidate()` | None (designed only) |
| Model registry lets one database hold several causal worlds; `USE MODEL` reuses the compiled snapshot | §3.3 | Derived worlds live in the DSL client (`dsl/model/ModelRegistry`), evaluated by `StructuralEvaluator` in client memory; plugin holds one snapshot | Contradicted by code |
| Empirical results | §8 | All `\TODO` | None |

## Strengths

### S1: The framing is the right one for ICDE and is executed consistently
The problem statement (§2.4, R1–R4) casts the work as query language + physical design + query processing + maintenance, the quantitative layer is explicitly demoted to "expressiveness, not a contribution" (§7), and the scope note for the submission form is already drafted. The related-work section positions the index in the 2-hop / GRAIL / PLL / TF-Label lineage. This is exactly what avoids the "belongs at UAI/CLeaR" desk rejection, and it is done without overselling.

### S2: The language-to-operator bridge is concrete and real
Table 1 (§3.2) maps every DSL statement to a graphical meaning and a physical procedure, and the code backs it: the grammar (`CausalDSL.g4`), the AST, the translator, 15 `@Procedure`s, and end-to-end tests over embedded Neo4j (`DslPluginE2ETest`, `CausalProceduresTest`, `CovidSeedDagE2ETest`). The two-target compilation (definition statements to `MERGE` + `causal.compile`, structural statements to `CALL causal.*`) is a clean design and easy for a reviewer to verify.

### S3: The snapshot and consistency model are well motivated
§4 explains why the snapshot must live outside per-call procedure instances, uses a single `volatile` reference with serialized compile/invalidate and lock-free reads, and enforces acyclicity at compile time via Kahn's algorithm so no downstream operator defends against cycles. This is the kind of implementation-level reasoning ICDE reviewers reward, and `RuntimeRegistry` / `DefaultCausalGraphRuntime` implement it as described.

### S4: The correctness core exists and is tested
Bayes-Ball with the "cut outgoing edges of the treatment" flag is a neat way to reuse one routine for both d-separation and the backdoor test (§5.4–5.5); the handling of observed descendants of colliders is correct in the code. 144 test methods across 14 test classes cover topological sort, path roles, d-separation, adjustment, linear-Gaussian inference, and end-to-end DSL execution. This gives the evaluation a trustworthy baseline to compare the index against, exactly as `IMPLEMENTATION-GUIDE.md` §1 plans (keep the DFS as the parity oracle).

### S5: Honest internal accounting
The `\NOTE` status blocks in §5, §6, §8 and the outline's "Reality check" mean the authors know precisely what is unbuilt. That discipline is unusual and makes the remaining work well scoped.

## Weaknesses

### W1: The paper's two central contributions are not implemented, so nothing in Table 2's "Index query" column or §8 can currently be measured
`CompiledCausalGraph` stores `Map<String,Set<String>> forwardAdj/reverseAdj` and `List<String> topoOrder`; `topoIndex()` is a linear `indexOf`; `ReachabilityIndex.ancestors/descendants` are iterative DFS; `DefaultCausalGraphRuntime` allocates a new `ReachabilityIndex` per call. There is no `topoLayer`, no `ancestorBits`, no `descendantBits`. No benchmark harness exists anywhere in the repository (no JMH, no dataset loaders for bnlearn, no synthetic DAG generator at the $10^2$–$10^4$ scale; `dev/synthetic.py` produces DoWhy linear datasets with tens of variables for the SEM tests, not scale-free DAGs). `IMPLEMENTATION-GUIDE.md` already lists this as the work; it must land before submission. Impact: without it the paper is a design paper with a plan for an evaluation, which ICDE will not accept as a regular research paper.

### W2: The complexity claims for d-separation and adjustment enumeration are not right, and a causal-graph-literate reviewer will say so
Table 2 states d-separation is $O(V/64)$ and adjustment sets are $O(k\,V/64)$. Bayes-Ball visits each (node, direction) state at most once and follows each edge; representing $Z$ and the visited set as bitsets makes membership $O(1)$ but the traversal is still $\Theta(V+E)$ in the worst case. The sentence in §5.4 "the whole test $O(V/64)$ in the frontier" does not define a cost model under which that holds. For adjustment sets, the code (`candidateAdjustmentSet`) tests the empty set, then every subset of eligible variables of size 1, 2, 3, … until one level succeeds. With $n$ eligible variables and a smallest valid set of size $s$, that is $\sum_{i\le s}\binom{n}{i}$ backdoor tests; on `munin` ($V=1041$) with $s=3$ this is roughly $1.9\times10^8$ Bayes-Ball runs. Speeding the inner test by a constant does not make identification scale. (The pathmc project has an open issue titled "adjustment_sets is exponential: identification unusable beyond ~16–20 nodes" for exactly this design.) Fix: rewrite the complexity story honestly (see suggestion 2 below) and replace the enumeration with the polynomial framework.

### W3: "Minimal adjustment sets" is not what the code computes, and the RQ5 oracle uses a different criterion
§5.5 says enumeration "yield[s] minimal adjustment sets"; the code returns all sets of minimum cardinality (first non-empty level) and stops. Inclusion-minimal sets of larger size are never returned. Also, the eligibility filter excludes all descendants of $T$ (Pearl's backdoor criterion); dagitty and pcalg implement the generalized adjustment criterion (Shpitser et al. 2010; Perković et al. 2018) whose forbidden set is $\mathrm{de}(\mathrm{cn}(T,Y))$, i.e. only descendants of nodes on causal paths. For RQ5 ("exact agreement of adjustment sets against dagitty") the two will disagree on any DAG where $T$ has a descendant not on a causal path to $Y$. Either implement the generalized criterion (the forbidden set is one intersection of bitsets, which is a selling point for the index) or restrict RQ5 to the backdoor sets dagitty can be asked for and say so.

### W4: §3.3 (model registry, causal worlds) describes something the system does not do
The section says the registry lets "a single database hold several causal worlds at once", that switching worlds "emits no Cypher", and that a later `USE MODEL` over a derived world "reuses the same compiled snapshot". In the code, `ModelRegistry` lives in the `dsl` module; `Neo4jQueryExecutor` evaluates structural statements over a derived model with `StructuralEvaluator` on an in-memory `CausalModel`, and `hydrateCurrentGraph()` pulls the graph into the client. The plugin's `RuntimeRegistry` holds exactly one snapshot. This contradicts §3.2's "the language never holds the graph in client memory". Either move world management server-side (named snapshots in `RuntimeRegistry`, `causal.compile(modelName)`, `do(X)` as a derived snapshot sharing the base's arrays) or rewrite §3.3 to describe the client-side registry as an implementation convenience and drop the snapshot-reuse claim.

### W5: Missing self-citation and unclear delta from the PVLDB 2025 vision paper
Pachera, Bonifati, Palmiotto, Mauri, "What If: Causal Analysis with Graph Databases", PVLDB 18(11), 2025 (also arXiv:2412.13965) proposes causal operators for property-graph query languages, discusses d-separation and adjustment sets, mentions views and incremental maintenance via triggers, and has a Neo4j 5.12 proof of concept. It is not in `refs.bib` and not mentioned in §9. ICDE's rule: work "described in any prior publication that is more than 4 double-column pages" must be cited and the overlap explained. The delta is large (that paper has no index, no compiled snapshot, no maintenance algorithm, no evaluation), so state it in one paragraph of §1 or §9. The Aug 2026 position paper by Qiu, Zhou, Pachera, Bonifati, Mauri (arXiv:2608.07214, "Toward a Causal Data Management Ecosystem") should also be cited for context.

### W6: Incremental maintenance is designed but its hard case is hand-waved
§6 gives the insertion patch (OR closures over the affected cone) and defers deletion to "recompute the affected cone or fall back". The affected-cone recompute for deletion is not specified: after deleting $u\to v$, the ancestors of every node in $\mathrm{de}(v)\cup\{v\}$ may shrink, and recomputing them means re-running the reverse-topological sweep restricted to that cone, which is bounded by $|\mathrm{de}(v)|\cdot(V/64)$ plus the parents' bitsets. Say this, give the bound, and make it the measured RQ3 point. Two smaller gaps: the pre-commit cycle check and the `active`/`latent` property-flip handling are listed as TODO in `CausalGraphChangeListener`, and `touchesCausalSchema` returns true for any created or deleted node, so the "cheap guard" claim in §6 is stronger than the code. Also note that the listener sees `active` on created edges but not flips, so today toggling `active` does not invalidate the snapshot, which is a correctness bug relative to §2.1.

### W7: The baselines cannot answer the same queries, and the paper does not say what each can do
§8 compares against plain Cypher variable-length paths and Neo4j GDS, but neither expresses d-separation or adjustment enumeration; the external libraries do. The evaluation needs a coverage matrix (operator × baseline) and must be explicit that Cypher/GDS are compared only on reachability and path-role operators, while d-separation and identification are compared against the DFS baseline and the external round-trip. Without this, a reviewer will read "N× faster than Cypher on adjustment sets" as comparing against nothing.

### W8: The memory trade-off of full-closure bitsets is asserted, not analysed
$O(V^2/64)$ words per closure means two closures on $V=10^4$ cost about 25 MB, on $V=10^5$ about 2.5 GB. The paper promises a "memory/latency crossover" study (§5.2, §8 RQ2) and "cost-based selection" in future work, but never states the crossover rule, and the layer-pruned DFS alternative is only mentioned. Give the formula, pick the default policy, and measure both branches.

## Methodology Assessment

| Criterion | Rating (1-5) | Assessment |
|---|:---:|---|
| Soundness | 3 | Bayes-Ball and backdoor test are correct; complexity claims for d-sep and enumeration are not; §3.3 contradicts the implementation |
| Novelty | 4 | Treating causal structural queries as an indexed DBMS workload is new; the delta over the authors' PVLDB 2025 vision must be stated |
| Reproducibility | 2 | Repo exists with tests, but no harness, no datasets in the required form, no artifact packaging; the paper promises one |
| Experimental Design | 3 | RQs, datasets, metrics are well chosen; baseline coverage is unclear; no results |
| Statistical Rigor | n/a | No measurements yet; protocol paragraph already asks for variance, warmup, repeats |
| Scalability | 2 | Enumeration is exponential in the outer loop; memory of closures unanalysed; scale-free generator absent |

## Questions for the Authors

1. Under what cost model is Bayes-Ball $O(V/64)$? If none, which operators are genuinely sublinear with the index (reachability, path roles, forbidden/canonical sets) and which only gain a constant?
2. Does `IDENTIFY EFFECT` return inclusion-minimal sets, minimum-cardinality sets, or one set? What does dagitty return in each mode you validate against?
3. Where do derived worlds (`INTERVENE`, `USE MODEL`) live and which engine answers structural queries over them?
4. How many d-separation tests does an identification query issue on `alarm`, `hepar2`, `andes`, `munin` with the current enumeration? This number decides whether RQ1 is measurable on the larger networks at all.
5. What is the expected write rate of a curated causal DAG? If edits are rare relative to queries, is invalidation plus recompile (sub-second for $V\le10^4$) already good enough, and what workload makes incremental maintenance necessary? RQ3 needs that argument.
6. How does the plugin behave under concurrent recompile and query? §4 claims snapshot isolation; is there a test?

## Minor Issues

- `main.tex` (Downloads copy) uses `\bibliography{biblio}` and `\bibliographystyle{plainurl}`; the file is `bibliography.bib` (empty) and IEEEtran expects `IEEEtran` style. The Desktop copy uses `refs.bib`. Pick one.
- Running example: paper says 25 variables and 76 edges; `dev/seed-dag.cypher` seeds 26 `Variable` nodes and 78 `CAUSES` edges (`README.md` also says 78, `CLAUDE.md` says 76). Verify against Piccininni et al. and make all four agree.
- Fig. 2 caption is the placeholder "Caption"; Fig. 1 has no descriptive caption beyond "Causal4j architecture".
- §2.2 defines "collider on a path" but §5.3 and `PathAnalyzer.colliders` compute a nonstandard set ("nodes with a parent reachable from $X$ and a parent reachable from $Y$ that ancestor neither"). Give a formal definition once and use it in both places.
- §2.4 last sentence: "R2- R4" has a stray space.
- Several sentences where "---" was replaced by ",  " now read oddly (double spaces, comma splices), e.g. §1 "standalone library,  DoWhy, dagitty, pgmpy, or causal-learn,  compute the answer". Since the thesis rule forbids em dashes, rewrite these as separate sentences or parentheses rather than comma pairs. The `\emph` rule from the thesis conventions is also violated throughout; decide whether it applies to this paper.
- Table 1 lists `BACKDOOR PATHS` mapping to `causal.backdoorVariables`, which returns variables on backdoor paths, not paths; the DSL keyword promises paths. Rename one side.
- §7 says the snapshot "reports itself parameterized only when some value departs from the defaults"; the code comment notes an all-identity SEM would be rejected. Mention or fix.
- `IEEEkeywords`, abstract, results preview, scope note, and the AI-generated-content acknowledgement (required by ICDE 2027) are all still `\TODO`.
- ORCIDs are required for all four authors at submission.
- Two files have uncommitted local modifications (`CausalGraphChangeListener.java`, `CounterfactualInference.java`) and `paper/`, `pipeline/`, the two design docs are untracked; the last commit is Jul 21 2026.

## Literature Positioning

The reachability-indexing lineage in §9 is right but the citations are all placeholders. The ones that matter, with their venues, so `refs.bib` can be filled: Cohen, Halperin, Kaplan, Zwick, 2-hop labels (SODA 2002 / SICOMP 2003); Yıldırım, Chaoji, Zaki, GRAIL (PVLDB 2010, VLDBJ 2012); Cheng, Huang, Wu, Fu, TF-Label (SIGMOD 2013); Akiba, Iwata, Yoshida, pruned landmark labeling (SIGMOD 2013, for shortest paths; the reachability variant is Yano et al. CIKM 2013); Seufert et al., Ferrari (ICDE 2013); Wei, Yu, Lu, Jin, IP+ (PVLDB 2014); Su, Zhu, Wei, Yu, BFL (PVLDB 2016); Zhou, Zhou, Yu, Wang, Cheng, DAG Reduction (SIGMOD 2017, VLDBJ 2018); Hanauer, Schulz, Trummer, O'Reach (JEA 2022); and a 2025 ACM Computing Surveys survey on reachability indexing exists and should be cited. Position the bitset closure honestly: it is the classical bit-vector transitive closure, chosen because causal DAGs are small enough and because the workload needs whole ancestor/descendant sets, not point-to-point tests. That argument is convincing; say it once and give the memory bound.

The causal-algorithmics literature is missing and is the more dangerous gap. Textor and Liśkiewicz (UAI 2011) and van der Zander, Liśkiewicz, Textor (UAI 2014; AIJ 2019, "Separators and adjustment sets in causal graphs: complete criteria and an algorithmic framework") give $O(V+E)$ tests for adjustment-identifiability via the canonical set $\mathrm{An}(T\cup Y)\setminus(\mathrm{Forb}\cup T\cup Y)$, linear-time construction of a minimal separator, and polynomial-delay enumeration of all minimal adjustment sets; van der Zander and Liśkiewicz (AISTATS 2020) find minimal d-separators in linear time; Shpitser, VanderWeele, Robins (UAI 2010) and Perković, Textor, Kalisch, Maathuis (JMLR 2018) define the generalized adjustment criterion. dagitty (Textor et al., IJE 2016) implements these, which is why it is the right oracle. The paper should cite them and build its enumeration on them; the closures it precomputes are precisely the sets these algorithms need, which turns a weakness into the strongest technical argument for the index.

Database-side causal work is also absent from §9 and reviewers from the DB community will expect it: Salimi et al., HypDB (PVLDB 2018) and CaRL (SIGMOD 2020); Galhotra, Gilad, Roy, HypeR (SIGMOD 2022); Youngmann et al., Causal Data Integration (PVLDB 2023); Meliou, Roy, Suciu, causality and explanations in databases (PVLDB 2014 tutorial). None of these indexes a causal DAG or offers structural operators over a stored graph, so the gap remains, but the paragraph must exist. Finally, GQL/SQL-PGQ (Deutsch et al., SIGMOD 2022; Francis et al., SIGMOD 2023) and the PVLDB 2025 vision paper (W5).

## Recommendations

**Overall Assessment (draft as it stands today)**: Reject, because the evaluation and the core index do not exist. **Projected assessment if the plan below lands**: Weak Accept to Accept. The idea is sound, the framing is right, and the delta over prior work is real.

**Confidence**: High on the code-versus-paper gap (read every relevant source file); medium on how ICDE reviewers weigh the enumeration issue (it is a correctness/complexity point a subset of reviewers will catch, but that subset is the one that matters).

**Contribution Level**: Moderate as drafted; Significant if the index, the polynomial identification algorithms over bitsets, and the incremental patch are built and measured.

### Actionable suggestions, in priority order

1. **Build the index and the harness first (blocking).** Implement the Tier-2 layout in `CompiledCausalGraph` (dense indices, `int[][]` CSR, `int[] topoOrder`, `int[] topoLayer`, `long[][] ancestorBits/descendantBits`), cache one `ReachabilityIndex` in `DefaultCausalGraphRuntime.compile`, and keep the DFS as `ReachabilityIndex.ancestorsDfs` for the parity test the implementation guide asks for. Write the harness the same week: bnlearn loaders (`.bif`/`.dsc` via pgmpy or bnlearn's RDS exports; the nine networks in the outline from `asia` (8) to `munin` (1041), plus `link` (724) and `pathfinder` (109) if wanted), a seeded scale-free DAG generator ($10^2$–$10^4$ nodes, density sweep, Barabási–Albert edges oriented by index), JMH or a warmup-and-repeat wall-clock loop, and a Python round-trip script for dagitty (via R or the JS port), DoWhy, pgmpy and causal-learn. Report medians with IQR over $\ge 5$ runs.

2. **Rewrite the complexity story so it is true, and make it stronger.** State three tiers in Table 2: (a) operators that are single bitset reads or intersections with the index: ancestors, descendants, `hasDirectedPath`, confounders, mediators, the forbidden set $\mathrm{de}(\mathrm{cn}(T,Y))$, the canonical adjustment set: $O(V/64)$ or $O(1)$; (b) Bayes-Ball d-separation: $O(V+E)$ with bitset membership, i.e. a constant-factor and allocation win over map-of-sets, measured not claimed; (c) identification: with the canonical-set test of van der Zander et al., "is the effect identifiable by adjustment" becomes one $O(V/64)$ set construction plus one d-separation test instead of $k$ tests; listing minimal sets uses their polynomial-delay enumeration, each step of which needs ancestor sets the index already holds. This replaces `combinationsOfSize` and is the single most valuable algorithmic change: it turns "we made the inner loop 64× faster" into "the index makes the linear-time identification algorithms run on precomputed sets".

3. **Fix the adjustment semantics before RQ5.** Decide between Pearl's backdoor criterion and the generalized adjustment criterion; implement the latter (forbidden set from bitsets) and validate against dagitty's `adjustmentSets` with matching options. Say precisely whether the procedure returns all minimal sets, minimum-cardinality sets, or one set (`EXPLAIN` could also return the canonical set as a witness).

4. **Make §3.3 true or remove the claims.** Cheapest: keep the client-side registry, describe it as such, delete "reuses the same compiled snapshot" and "never holds the graph in client memory". Better, if time allows: named snapshots server-side (`causal.compile(name)`, `causal.use(name)`), with $do(X)$ producing a derived snapshot that shares the base's bitsets except for the rows of $X$'s former ancestors. This also gives §6 a second maintenance case (derive a world incrementally rather than recompile).

5. **Land incremental maintenance for insertion and property flips; specify deletion.** Wire `applyChange(ChangeBatch)`, implement the affected-cone OR-in for edge insertion, the pre-commit cycle check (`descendantBits[v]` contains $u$), `active`/`latent` flips in `beforeCommit`, and a cone-restricted recompute for deletion with its bound stated. RQ3 then measures three edit types against full recompile with the batch-size crossover. If deletion slips, say "insertion and flips patch; deletion recompiles" in §6 and §8 rather than "designed".

6. **Add the coverage matrix and the memory analysis to §8.** Operator × baseline table stating which baseline can answer which operator; the $O(V^2/64)$ memory formula with the measured resident set for each dataset; and the crossover policy (e.g. use closures when $2\cdot V^2/8 \cdot 1/64$ bytes is below a configured budget, otherwise layer-pruned DFS), measured on both branches.

7. **Cite prior work properly.** Fill `refs.bib` with real entries (list above), add the PVLDB 2025 vision paper and its delta, add the causal-algorithmics and DB-causality paragraphs to §9.

8. **Use what the repository already has for a second contribution the paper does not mention.** The Python `pipeline/` module (discover → resolve → fit → refute → emit `.causal`) is a complete ingestion path that produces grammar-valid DSL with provenance sidecars. It is not in the paper. Half a page in §3 or §7 ("populating the store: the DAG is authored by hand or produced by an upstream pipeline that emits DSL; the DBMS never fits parameters") strengthens the "database, not ML" boundary reviewers care about and makes the artifact end-to-end (data → DAG → DB → queries). Keep it short; it is context, not a contribution.

9. **Consider one more workload-level experiment that only an in-DBMS design can do.** A mixed workload: continuous curation edits interleaved with identification queries, measuring query latency under updates for (i) external round-trip (re-export on each edit), (ii) invalidate-and-recompile, (iii) incremental patch. This is the experiment that justifies R4 and the whole "inside the DBMS" argument in one figure, and it is cheap once the harness exists.

10. **Page budget.** Sections 1–7 as drafted are roughly 7 pages of prose before figures and tables; §8 with five RQs and two figures plus a dataset table needs 2.5–3 pages; §9 needs a full page once the missing paragraphs are added. Trim §7 (quantitative layer) to half a page and §3.3 to a paragraph to stay at 12.

### What the draft already does well and should keep

The problem statement with R1–R4, the two-target compilation, Table 1, the snapshot/consistency discussion in §4, the honest "index versus layer-pruned DFS" trade-off framing, and the future-work list (ADMG/latent projection, batched enumeration, cost-based selection). The writing is clear and the structure matches the outline; the work that remains is engineering and measurement, not rethinking.

## Limitations of this review

I did not run the test suite or the DSL; claims about the code are from reading the sources. I could not open the PDF of the Aug 2026 position paper beyond its HTML abstract. I did not verify the Piccininni et al. DAG against the original article. The projected assessment assumes ICDE 2027 Round 2 reviewers apply the same criteria as the 2026 CFP, which the 2027 CFP restates.

Sources consulted: [ICDE 2027 Call for Research Papers](https://icde2027.github.io/cf-research-papers.html), [What If: Causal Analysis with Graph Databases, PVLDB 18(11)](https://www.vldb.org/pvldb/vol18/p4009-pachera.pdf), [arXiv:2412.13965](https://arxiv.org/abs/2412.13965), [Toward a Causal Data Management Ecosystem, arXiv:2608.07214](https://arxiv.org/html/2608.07214), [van der Zander, Liśkiewicz, Textor, AIJ 2019](https://www.sciencedirect.com/science/article/pii/S0004370219300025), [van der Zander & Liśkiewicz, minimal d-separators in linear time](http://proceedings.mlr.press/v115/van-der-zander20a/van-der-zander20a.pdf), [pathmc issue on exponential adjustment enumeration](https://github.com/pymc-labs/pathmc/issues/486), [DAG Reduction, SIGMOD 2017](https://dl.acm.org/doi/10.1145/3035918.3035927), [O'Reach, JEA 2022](https://dl.acm.org/doi/full/10.1145/3556540), [Indexing Techniques for Graph Reachability Queries, ACM CSUR](https://dl.acm.org/doi/10.1145/3776737), [HypeR, SIGMOD 2022](https://dl.acm.org/doi/10.1145/3514221.3526149), [CaRL, SIGMOD 2020](https://users.cs.duke.edu/~sudeepa/papers/CARL-SIGMOD2020.pdf), [Causal Data Integration, PVLDB 2023](https://dl.acm.org/doi/10.14778/3603581.3603602), [bnlearn Bayesian Network Repository](https://www.bnlearn.com/bnrepository/).
