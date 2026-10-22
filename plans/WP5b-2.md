# WP5b-2: tiers (b) and (c), RQ5, RQ3

Instructions for Claude Code (owner: A). Read `plans/WP5b.md` (Phase 2 section),
`plans/WP5b-1-followup.md`, `plans/WP2.md` and `plans/WP3.md` first. Branch
`feat/bench-phase2` from `feat/index` (after #35, #32, #33 and the remove-paper PR). One PR against `feat/index`, one commit per step.

## Preconditions (check before writing code)

- WP2 is merged into `feat/index`: `runtime/algorithms/DSeparation`, `Adjustment`
  (`decide`, `isValid`, `findMinimal`, `listMinimal`), `PathAnalyzer` over bitsets, the
  `causal.adjustmentSets(t, y, mode, limit)` procedure, the `causal.dsep.mask` system
  property, and the `*Reference` classes in test scope. If any is missing, stop and say
  which.
- WP3 is merged: `SnapshotPatcher.apply` handles insertion with relabel, deletion by cone
  recompute with threshold `causal.patch.threshold`, variable removal, and
  `causal.inspectSnapshot` exposes the fallback counter. If WP3 is not merged, do Steps
  1–2 only and open the PR for them; Step 3 waits.
- `bench/workloads/<ds>/triples_balanced.csv` exists (WP5a `workloads --balanced`); if
  not, generate it first with the WP5a package (25 separated + 25 connected per `|Z|`,
  seed 20261111, rejection against the pgmpy verdict).
- The paper's `numbers-defaults.tex` lists every macro to emit; read it and emit all.
- **Added Oct 14.** The landscape paper's running example is a new DAG,
  `dev/seed-policy-dag.cypher` (22 variables, 3 latent, 37 edges; also
  `bench/datasets/policy/edges.csv`). Add it to the WP5a dataset set as `policy`
  (reference answers from dagitty with its latent flags, workloads, Table III row) and
  run every Phase 1 and Phase 2 benchmark on it exactly as on `covid`; both DAGs with
  latent variables are reported.
- **Updated Oct 16.** The bench output move (`bench/out/<run-id>/` only, export builds the
  three paper versions) is done on `feat/index-remove-paper`; that PR must be merged into
  `feat/index` before this branch is cut. Check that `bench/export_paper.sh` builds
  `main.tex`, `full_version.tex` and `technical.tex`; if it does not, stop and say so.
- **Added Oct 14.** `PathAnalyzer.backdoorVariables` uses the generalized-criterion
  barrier `forb(t,y) \ {y}` since WP2; the DFS baseline (`PathAnalyzerReference`) was
  changed to match, so the tier (a) comparison is on the same operator.

## House rules

As in WP5b: JMH sample-time, 3 forks for in-process operators, medians and p99, outputs
under `bench/results/<run-id>/`, merge with the Phase 1 runs in `results_to_tex.py` by
(dataset, operator, system). Everything reads WP5a's files and nothing synthesizes data
in Java. Hard 60 s bound per censored baseline. Never commit in the paper repository.

## Step 1: tiers (b) and (c) latency (RQ1 completion, RQ4 panels)

`jmh/TierBCBenchmarks`, `@State` holding the snapshot, the pairs, the balanced triples,
and the reference answers; `@Param` over the same dataset set as `TierABenchmarks`.

Benchmarks (one workload item per invocation, rotating):
- `dsepMasked` and `dsepUnmasked`: `DSeparation.separated` with `causal.dsep.mask`
  true/false (set the property per fork; two `@Param` values), over
  `triples_balanced.csv`, reporting medians separately for separated and connected
  verdicts (tag each sample with the reference verdict in the side CSV).
- `dsepReference`: the `DSeparationReference` (July Bayes-Ball over `Set<String>`),
  same triples, for the DFS-baseline column.
- `decide`: `Adjustment.decide(t, y)` over `pairs.csv`.
- `decideReference`: the July level-wise enumeration (minimum-cardinality backdoor sets
  by `combinationsOfSize`), ported into `bench/.../LegacyEnumeration.java` from
  `git show v0-dfs-baseline:plugin/src/main/java/com/example/causal/runtime/DefaultCausalGraphRuntime.java`
  (it is deleted from the plugin by WP2); wrapped in a 60 s timeout per pair, censored
  pairs recorded. Run it on bnlearn and covid only; it is expected not to finish on
  `andes` and above.
- `listMinimal100`: `Adjustment.listMinimal(t, y, limit=100)`, counting emitted sets.
- `findMinimal`: one minimal set.
- External: extend `roundtrip.py` so `dsep` and `adjustment` run on the balanced triples
  and pairs for pgmpy and dagitty (already partly there from Phase 1; reuse).

Outputs: `tables/rq1_tier_bc.tex` (rows: datasets; columns: dsep masked / unmasked /
reference / pgmpy / dagitty, decide / legacy enumeration (with censored count) /
pgmpy backdoor / dagitty canonical / DoWhy, listMinimal100, findMinimal); RQ4 figure
gains two panels (masked d-separation and `decide` against `|V|` on the synthetic grid,
same style as the existing two; keep the file name `rq4_latency.pdf`, now four panels in
a 2×2 grid at `\linewidth`). Macros: `\speedupDsepMasked` (masked over unmasked, median
over bnlearn + covid, connected and separated pooled), `\speedupDecideVsEnumeration`
(legacy over decide, median over the pairs where the legacy finished, plus the number of
censored pairs reported in the PR description).

## Step 2: RQ5 correctness

`bench/scripts/rq5.sh` and `bench/src/.../Rq5Runner.java`: for every dataset with a
`bench/reference/<ds>/` directory, run Causal4j in process:
- every triple of `triples.csv` and `triples_balanced.csv` through `DSeparation.separated`
  (masked), compare with `dsep.json`;
- every pair of `pairs.csv` through `Adjustment.decide`, compare `identifiable` and the
  canonical set with `adjustment.json` (`canonical` is a set; compare as sets);
- for pairs whose reference `minimal` is non-null, `listMinimal` with no limit, compare
  the family as a set of sets; for pairs with `minimal == null`, `listMinimal(limit=100)`
  and write the emitted sets to `rq5_to_validate.json`, then call
  `causal4j-bench reference --validate rq5_to_validate.json` (WP5a), which checks each
  set with dagitty's `isAdjustmentSet` and removal-minimality and writes the verdicts.

Outputs: `rq5.csv` (per dataset: triples, agreeing, pairs, canonical agreeing,
identifiable, not identifiable, families compared, families equal, families validated
per set, sets validated, sets failing), `tables/rq5.tex`, `rq5_disagreements.csv` with
both answers for every mismatch (the run does not fail on a mismatch). Macros:
`\numDsepQueries`, `\numDsepAgree`, `\numPairs`, `\numCanonicalAgree`,
`\numPairsNotIdentifiable`, `\numFamiliesCompared`, `\numFamiliesEqual`,
`\numFamiliesChecked` (= pairs validated per set). Format numbers with thin-space
thousands separators as `results_to_tex.py` already does.

## Step 3: RQ3 incremental maintenance (needs WP3)

`jmh/MaintenanceBenchmarks` plus `bench/scripts/rq3.sh`.

Edit streams: for every bnlearn dataset, covid, and the ER/BA graphs with $n \in \{10^3,
10^4\}$, $\bar d = 4$, seed 0: random edit sequences from a fixed seed with edit types
{insert acyclic edge, delete existing edge, `active` flip off/on, `latent` flip, add
variable with one edge, remove variable}; batch sizes {1, 10, 100, 1000}. For each
(dataset, type, batch size): `SnapshotPatcher.apply(snapshot, batch)` against
`GraphBuilder.build` of the updated edge list (the recompile), both JMH-timed; record
the cone sizes `|an(u)|+|de(v)|` of every edit and the fallback counter delta from
`causal.inspectSnapshot`-equivalent accessor. Sweep `causal.patch.threshold` over
{|V|/8, |V|/4, |V|/2, |V|} on the deletion stream to locate where the patch stops paying.

Mixed workload: in embedded Neo4j with the listener active, a stream of 10,000 queries
(random tier-a and `decide` queries from the workload) with one edit every $k \in \{1,
10, 100\}$ queries, measuring query latency (median, p99) under three policies:
(i) patch (default), (ii) invalidate-and-recompile (`causal.patch.enabled=false`, a
system property to add if WP3 did not), (iii) external re-export, computed, not re-run:
per edit, the measured export + build cost of pgmpy from Phase 1 for that dataset is
added to the query stream as a stall, and queries are charged pgmpy's measured per-query
cost; state in the table that policy (iii) is composed from Phase 1 measurements.

Outputs: `tables/rq3.tex` (patch vs recompile by type and batch size, bnlearn summary
plus the two synthetic sizes), `figures/rq3_incremental.pdf` (panel 1: patch/recompile
ratio against batch size per edit type; panel 2: query p99 against $k$ under the three
policies), macros `\patchVsRecompileInsert` and `\patchVsRecompileDelete` (median ratio
patch/recompile at batch size 1, formatted as a percentage string such as `4\%`),
`\fallbackThreshold` (the threshold, as a fraction of $|V|$, above which recompile is
cheaper on the deletion stream), `\mixedWorkloadSpeedup` (p99 of policy (ii) over policy
(i) at $k = 10$, median over datasets).

## Step 4: export and docs

`results_to_tex.py` emits every macro in `numbers-defaults.tex` with a value (no `\TODO`
left) and the new tables and figures; `export_paper.sh <run-id>` copies them, runs
`latexmk` on `main.tex`, `full_version.tex` and `technical.tex`, and prints the page counts.
`bench/README.md`: add the Phase 2 scripts to the mapping table and the total
`run_all.sh` wall time. CI `bench-quick` gains one `decide` and one `dsep` call on asia.

## Done when

- `grep -c TODO ../Causal4j-paper/numbers.tex` after export is 0.
- `rq5_disagreements.csv` is empty, or every row is explained in the PR description.
- The legacy enumeration's censored-pair count and the datasets it did not finish are in
  the PR description.
- Both papers compile after the export; page counts reported.

## Out of scope

Statistical tests beyond medians and percentiles; re-running the dagitty reference
families (WP5a); any edit to the paper text.
