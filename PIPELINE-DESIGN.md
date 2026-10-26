# Y-DBMS Ingestion Pipeline — Design Document

Status: DRAFT for review. Nothing here is implemented yet.

## 1. Purpose and positioning

Everything statistical or ML-based that a database cannot (and should not) do
happens **upstream, in Python**: causal structure discovery from data, model/
hypothesis-driven fitting of structural equations, refutation and diagnostics.
The database receives only the finished *structural artifact* — a DAG plus its
SEM parameters — expressed in our DSL.

```
                    ┌────────────────────── Python (this tool) ──────────────────────┐
 data (csv/parquet) │ load → preprocess → [discover DAG | accept DAG] → validate     │
 DAG (optional)   ──┤        → fit SEM (hypothesis) → refute/diagnose → emit DSL     ├──▶ model.causal
 priors/constraints │                                                                │        │
                    └────────────────────────────────────────────────────────────────┘        ▼
                                                                              DSL REPL / executor → Neo4j
                                                                              (existing Java toolchain,
                                                                               unchanged)
```

Division of labour is strict:

| Concern                          | Where            |
|----------------------------------|------------------|
| Discovery, fitting, refutation   | Python pipeline  |
| DAG storage, structural queries, index, incremental maintenance | Neo4j plugin |
| Loading `.causal` into the DB    | existing DSL REPL/executor (`dsl` module) — **not** re-implemented in Python |

The pipeline's only output contract is a syntactically valid, semantically
complete `.causal` file. It never talks Cypher and never bypasses the DSL.

## 2. Two entry modes

**Mode A — discovery ("I have data, no DAG").**
Tabular data in → causal discovery algorithm → DAG out. Discovery algorithms
generally return an equivalence class (CPDAG/PAG), not a DAG, so this mode
includes a mandatory *resolution* step (§5.3). Optionally continues into SEM
fitting (Mode B's second half) so a single run yields a fully parameterized model.

**Mode B — hypothesis ("I have the DAG, fit the model").**
DAG (file, or discovered previously) + data + a model hypothesis (`linear-gaussian`
first; the mechanism registry in §6 keeps it open) → fit structural equations per
node → emit DSL with `WEIGHT` / `MEAN` / `NOISE` filled in.

Both converge on the same tail: validate → emit.

## 3. Inputs

- **Data**: CSV / Parquet via pandas. Column names become variable names
  (sanitized to DSL `ID`: `[a-zA-Z_][a-zA-Z0-9_]*`).
- **DAG** (Mode B): GML, DOT, edge-list CSV, or `.causal` itself (round-trip).
- **Priors / background knowledge** (optional, both modes): required edges,
  forbidden edges, temporal tiers, known latents. Fed into discovery as
  constraints and into validation as assertions.
- **Existing DB graph** (optional): a source adapter can pull the current DAG
  from Neo4j (bolt) to serve as the input DAG or as prior knowledge — this and
  optional GDS calls (e.g. degree/community stats to sanity-check a discovered
  structure against the domain graph) are the only DB touchpoints, and both are
  read-only.
- **Config**: one YAML file drives the whole run (§7).

## 4. Libraries

The pywhy stack, plus glue:

- **causal-learn** — discovery: PC, FCI, GES, exact search, LiNGAM family.
- **dowhy** (incl. `dowhy.gcm`) — graph-based identification checks, SEM/mechanism
  fitting, refutation tests.
- **networkx** — internal graph representation (single in-memory format all
  stages share).
- **pandas / numpy / scikit-learn** — data handling and estimators.
- **neo4j** Python driver — read-only source adapter only (§3).

## 5. Pipeline stages

Each stage is a small, replaceable unit with a declared input/output type;
the runner chains them from config. Users can skip, reorder (where legal), or
substitute their own implementation of any stage (§6).

### 5.1 Load & preprocess
Read data, coerce dtypes, map columns → variable declarations (name, `TYPE`
binary/continuous/categorical inferred or declared, `ROLE` from config),
handle missingness policy (drop/impute — explicit, never silent).

### 5.2 Discover (Mode A only)
Run the configured discovery algorithm with the configured hyperparameters
(independence test, alpha, score, …) and the prior-knowledge constraints.
Output: CPDAG/PAG/DAG as networkx + metadata (algorithm, params, seed).

### 5.3 Resolve & validate
- Resolve equivalence class → single DAG: orientation rules + user constraints
  + interactive or fail-fast policy for edges that remain undirected (config
  choice; never silently pick an orientation without recording it).
- Assert acyclicity, connectedness expectations, required/forbidden edges.
- Diff against DB graph if the source adapter is active ("what would change").

### 5.4 Fit SEM (Mode B, or Mode A continuation)
Given DAG + data + hypothesis, fit each node's structural equation. For
`linear-gaussian`: per-node linear regression on parents → edge coefficients β
(`WEIGHT`), intercept μ₀ (`MEAN`), residual variance σ² (`NOISE`). This maps
1:1 to what the plugin's `LinearGaussianInference` consumes. Latent variables
are declared (`TYPE latent`) but not fitted.

### 5.5 Refute & diagnose
Optional but recommended gate: dowhy refutation tests (placebo, subset, random
common cause), conditional-independence checks of the DAG against the data,
goodness-of-fit per equation (R², residual normality for the Gaussian
hypothesis). Results go into the run report; config decides whether failures
block emission or just warn.

### 5.6 Emit DSL
Serialize to a `.causal` file using the actual grammar
(`dsl/src/main/antlr4/.../CausalDSL.g4`):

```
DEFINE MODEL <Name> {
  VARIABLE Age        TYPE continuous ROLE covariate MEAN 42.1 NOISE 3.2
  VARIABLE Hidden     TYPE latent
  ...
  DAG {
    Age -> Treatment  WEIGHT 0.83
    ...
  }
}
```

plus a machine-readable **provenance sidecar** (`<name>.provenance.json`):
data hash, algorithm + hyperparameters + seed, library versions, fit
diagnostics, refutation outcomes, timestamp. The `.causal` file is what the DB
loads; the sidecar is what makes the run auditable and reproducible.

## 6. Customizability model

This is the "usable and customizable tool" requirement, concretely:

- **Registries, not if/else.** Discovery algorithms, mechanism/hypothesis types,
  resolution policies, refuters, and I/O formats each live in a registry keyed
  by name. Config selects by name; adding a new algorithm = registering one
  class/function, no core changes. A plain Python entry-point hook lets users
  register their own from outside the package.
- **Three usage levels:**
  1. **CLI** — `ydbms-pipeline discover|fit|emit|run` (`run` = full chain),
     everything driven by the YAML config; `--dry-run` prints the DSL to stdout.
  2. **Python API** — `Pipeline.from_config(...)` / builder-style
     `Pipeline().discover(PC(alpha=.05)).resolve(...).fit(LinearGaussian()).emit(...)`
     for notebooks.
  3. **Stage functions** — every stage importable and usable standalone
     (a user who only wants "fit SEM on my DAG" calls that one function).
- **Config over code**: a run is fully described by one YAML file → identical
  reruns, easy sweeps.

## 7. Config sketch

```yaml
run:
  name: covid_v2
  seed: 42
data:
  path: data/covid.parquet
  roles: { interventions: treatment, reported_cases: outcome }
  types: { interventions: binary }        # else inferred
dag:                                       # omit in Mode A
  path: dags/covid.gml
discovery:                                 # omit in Mode B
  algorithm: pc                            # registry key
  params: { alpha: 0.05, indep_test: fisherz }
  knowledge:
    required: [[covid19_burden, exposure_sarscov2]]
    forbidden: [[reported_cases, interventions]]
resolve:
  undirected_policy: fail                  # fail | ask | prior | heuristic
model:
  hypothesis: linear-gaussian              # registry key
refute:
  tests: [placebo, subset]
  on_failure: warn                         # warn | block
emit:
  model_name: CovidModel
  out: out/covid.causal
  provenance: true
```

## 8. Repository layout

```
pipeline/
  pyproject.toml            # package: ydbms-pipeline; pinned pywhy deps
  README.md                 # usage, examples
  ydbms_pipeline/
    cli.py                  # subcommands
    config.py               # YAML schema + validation
    pipeline.py             # runner / builder API
    registry.py             # the extension mechanism
    stages/                 # load, discover, resolve, fit, refute
    io/                     # readers (gml/dot/csv/causal), neo4j source adapter
    emit/                   # DSL serializer + provenance
  tests/
  examples/                 # covid end-to-end, synthetic end-to-end
```

Python ≥3.10. The `pipeline/` directory is independent of the Maven build —
no Java changes required anywhere.

## 9. Validation & testing

- **Round-trip gate (the critical one):** every emitted `.causal` file must
  parse and load through the existing DSL toolchain. Test by feeding emitted
  files to the `dsl` module's dry-run REPL (`CypherDryRunRepl`) in CI; a
  syntactically drifted emitter fails immediately.
- **Recovery test:** generate data from a known SEM (e.g. via `dowhy.gcm` or
  `dev/synthetic.py`'s configs), run Mode A, assert the discovered structure
  matches (up to equivalence class) and Mode B recovers the true β/σ² within
  tolerance.
- **Determinism:** same config + seed ⇒ byte-identical `.causal` output.
- **COVID example:** `examples/` reproduces the paper's running example
  end-to-end: data → discovery/fit → `covid.causal` → loadable by `dev/seed.sh`'s
  successor.

## 10. Explicitly out of scope

- Writing to Neo4j from Python (loading stays in the Java DSL executor).
- Any new DSL syntax — the emitter targets the grammar as it exists; if a
  hypothesis someday needs syntax the DSL lacks (e.g. non-Gaussian noise
  declarations), that's a DSL-module change to be designed separately.
- Estimation/inference at query time — that's the plugin's quantitative layer.
- Streaming/online ingestion; this is a batch tool.

## 11. Open questions for review

1. **CPDAG resolution default** — fail-fast (safe, annoying) or
   prior-constrained heuristic (convenient, riskier)? Draft says `fail`.
2. **GDS scope** — is read-only "sanity stats + DB-as-DAG-source" enough, or is
   there a concrete GDS computation the pipeline should depend on?
3. **Hypotheses beyond linear-gaussian** — the registry keeps the door open, but
   the DSL can only carry β/μ₀/σ² today. Accept that limit for v1?
4. **Where does the run report live** — sidecar JSON only, or also a human
   `report.md` per run?
5. **Paper impact** — this likely becomes a short "ecosystem/ingestion" subsection
   (§3 or a new §) rather than a headline contribution. Agree?

## 12. Milestones

1. **Skeleton** — package, config, registry, CLI plumbing; emitter for
   hand-written models; round-trip gate green on `dev/example.causal`-style files.
2. **Mode B** — DAG readers + linear-gaussian fitting + provenance; recovery
   test for parameters.
3. **Mode A** — causal-learn adapters (PC, GES, LiNGAM) + knowledge constraints
   + resolution policies; recovery test for structure.
4. **Refutation & polish** — dowhy refuters, diagnostics report, COVID example,
   docs.

Each milestone is independently shippable; Mode B before Mode A because it has
no equivalence-class ambiguity and exercises the full emit path sooner.
