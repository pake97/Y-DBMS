# WP5b-1 follow-up: three measurements to fix before the RQ1/RQ2/RQ4 text is written

Branch from `feat/bench-harness` (or push to it before #33 merges). Re-run only the
affected benchmarks; reuse run `20261002T201303Z-4fb92af` for everything else and let
`results_to_tex.py` merge runs by (dataset, operator, system).

## 1. Causal4j through the procedure, not in-process (fairness of the Cypher/GDS comparison)

`TierABenchmarks` calls `reach.ancestors(...)` directly in the JVM, while the Cypher and
GDS baselines pay query parsing, planning, transaction and result materialization inside
Neo4j. The 135x figure therefore compares a Java method with a database query. Add
`jmh/ProcedureBenchmarks` with the same five operators executed as
`CALL causal.ancestors($v)` etc. through `GraphDatabaseService.executeTransactionally`
on the same embedded instance and the same workload, 1 fork, 3 warm-up and 5
measurement iterations, exhausting the result stream. Report three Causal4j columns in
`rq1_tier_a.tex`: `in-process`, `procedure`, and keep `DFS`. The paper's comparison with
Cypher and GDS uses the `procedure` column; `in-process` shows the floor. Macro
`\speedupProcedureVsCypher` replaces `\speedupTierAvsCypher` in the paper text;
keep both defined.

## 2. Separate the closure read from result materialization

`ancestors()` returns `Set<String>`, so its cost is dominated by building a
`LinkedHashSet` of ids, which grows with the answer and is paid by the DFS too; Fig. RQ4
shows the two curves overlapping for that reason, while `reaches` is flat at 0.04 us.
Add two benchmarks to `TierABenchmarks`: `ancestorsBits` returning the `long[]` row
(what tiers b and c consume) and `ancestorsCount` returning `BitSet64.cardinality`
of it; both with `index` on only (there is no DFS equivalent without materialization).
Add one column `closure read` to `rq1_tier_a.tex` for ancestors and descendants, and
macro `\closureReadUsMunin`. This is the number that supports the O(w) row of Table II;
the materialized number supports nothing asymptotic and the text will say so.

## 3. RQ2 latency columns disagree with RQ1

`rq2.tex` reports ancestors on asia at 5.23 us (closures) and 2.85 us (pruned), while
`rq1_tier_a.tex` reports 0.08 us for the same operator and dataset; small graphs are
slower than munin in RQ2, which cannot be right. Find the cause (likely: the RQ2 latency
loop is not a JMH benchmark, constructs a `ReachabilityIndex` per call, or reads the
`causal.closures.budget`/`causal.index` system properties per call), fix it so RQ2
latencies come from the same JMH class with `@Param budget`, and regenerate `rq2.tex`
and `rq2_crossover.pdf`. Until the two tables agree on the closure branch for every
shared dataset, RQ2 latency is not reported.

## Done when

- `rq1_tier_a.tex` has columns in-process / procedure / DFS / Cypher / GDS plus a
  closure-read column; `rq2.tex` closure-branch latencies equal `rq1` within noise.
- `numbers.tex` defines `\speedupProcedureVsCypher`, `\closureReadUsMunin`, and
  `\procedureOverheadUs` (median of procedure minus in-process on munin).
- PR #33 description updated with the three new numbers.

## 4. Macro completeness check and export (paper repository changed on Oct 5)

The paper now inputs `numbers.tex` (exported, overwritten) and then
`numbers-defaults.tex` (committed, every macro with a `\TODO` default). Change
`results_to_tex.py` so the completeness check reads the macro names from
`../Causal4j-paper/numbers-defaults.tex` (every `\providecommand{\name}` there) instead of
a grep pattern over the sections, and emits every one of them: measured value when
available, `\TODO{...}` otherwise. New macros the paper uses: `\speedupProcedureVsCypher`,
`\procedureOverheadUs`, `\closureReadUsMunin`. `export_paper.sh` copies `tables/*.tex`
into `<paper>/tables/`, `figures/*.pdf` into `<paper>/figures/`, and `numbers.tex` into
`<paper>/numbers.tex`; the paper wraps each `\input`/`\includegraphics` in
`\IfFileExists`, so a missing file degrades to a placeholder rather than an error.
After the export, `latexmk -pdf main.tex` in the paper directory must finish with no
errors; run it as the last step of `export_paper.sh` and print the page count.

## 5. Table V layout

`rq1_tier_a.tex` will gain two Causal4j columns (procedure, closure read). Keep the
column order per operator as: Causal4j in-process, Causal4j procedure, DFS, Cypher, GDS;
drop the `descendants` block from the paper table (it mirrors ancestors; keep it in the
CSV) so the table stays legible at `\textwidth`.
