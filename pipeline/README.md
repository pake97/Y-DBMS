# causal4j-pipeline

Ingestion pipeline for [Causal4j](../README.md): everything statistical or
ML-based that the database cannot do happens here, upstream, in Python —
causal structure discovery, hypothesis-driven SEM fitting, refutation. The
output is a grammar-valid `.causal` DSL file (plus a provenance sidecar);
loading into Neo4j stays with the Java DSL executor. See
[PIPELINE-DESIGN.md](../PIPELINE-DESIGN.md) for the full design.

## Install

```bash
cd pipeline
pip install -e .                    # core: emit + Mode B (fit)
pip install -e ".[discovery]"       # + causal-learn (PC, GES, LiNGAM)
pip install -e ".[refute]"          # + dowhy refuters
pip install -e ".[neo4j]"           # + read-only DB source adapter
```

## Two modes

**Mode B — you have the DAG, fit the model:**

```bash
causal4j-pipeline run examples/covid.yaml
```

**Mode A — discover the DAG from data:**

```bash
causal4j-pipeline run examples/discovery.yaml
```

Either way the result is `out/<name>.causal` + `out/<name>.provenance.json`.
Load the `.causal` file with the DSL REPL (`dsl` module) — the pipeline never
writes to the database.

## CLI

```
causal4j-pipeline run <config.yaml> [--dry-run]    # full chain
causal4j-pipeline discover <config.yaml>           # Mode A, structure only
causal4j-pipeline fit <config.yaml>                # Mode B
causal4j-pipeline emit <dag-file> [--out X]        # convert gml/dot/csv -> .causal
causal4j-pipeline validate <file.causal>           # structural round-trip check
causal4j-pipeline registry                         # list available algorithms
```

## Python API

```python
from causal4j_pipeline import Pipeline

result = (Pipeline(seed=42)
          .with_data("data.csv", roles={"T": "treatment", "Y": "outcome"})
          .discover("pc", alpha=0.05)      # or .with_dag("dag.gml")
          .resolve("prior")
          .fit("linear-gaussian")
          .refute("ci", "gof")
          .emit("out/model.causal", model_name="MyModel"))
print(result.dsl_text)
```

## Extending

Register a new discovery algorithm, hypothesis, resolution policy, refuter, or
reader by name — config selects it, no core changes:

```python
from causal4j_pipeline.registry import HYPOTHESES

@HYPOTHESES.register("my-hypothesis")
def fit_mine(model, df, **params):
    ...  # set model.variables[v].mean/.noise and edge weights
    return {"hypothesis": "my-hypothesis"}
```

External packages can do the same through the `causal4j_pipeline` entry-point
group.

## Guarantees

- **Grammar-valid output**: the emitter targets `CausalDSL.g4` exactly (no
  scientific notation, keyword-safe identifiers, ordered options).
- **Deterministic**: same config + seed → byte-identical `.causal`.
- **Auditable**: every run writes a provenance sidecar (data hash, algorithm,
  hyperparameters, seed, versions, diagnostics, every orientation decision).
- **Never guesses silently**: unoriented CPDAG edges fail the run unless a
  policy is chosen; heuristic orientations are recorded as guesses.

## Testing

### Automated suite

```bash
pip install -e ".[dev]"          # core + pytest
pip install -e ".[discovery]"    # add causal-learn to exercise Mode A
pytest -q
```

Expect **22 passed, 1 skipped**. The skip is the Mode A discovery test, which
activates once `[discovery]` (causal-learn) is installed.

### Manual walk-through

Run these from the `pipeline/` directory. If the `causal4j-pipeline` script is
not on your PATH (it installs to `~/.local/bin`), substitute
`python -m causal4j_pipeline.cli` for `causal4j-pipeline`.

**1. Recover a known SEM end-to-end.** Generates data from a SEM with true
coefficients (0.9, 0.7, −0.5, 1.2, 0.2), fits, and prints the emitted `.causal`:

```bash
python examples/synthetic_end_to_end.py
```

Check the `WEIGHT` values in the output against those true coefficients — they
should match to about two decimals — and that `refutation passed: True`. With
causal-learn installed this runs Mode A (discovery); otherwise Mode B (known DAG).

**2. Exercise the CLI.**

```bash
causal4j-pipeline registry                        # list algorithms/hypotheses/policies
causal4j-pipeline emit out/synthetic.causal        # DAG file -> .causal (round-trip)
causal4j-pipeline validate out/synthetic.causal    # structural check (acyclic, declared, edges)
causal4j-pipeline run examples/covid.yaml --dry-run  # full chain, prints DSL, writes nothing
```

**3. Verify the real DSL gate.** The pipeline's own `.causal` reader is used for
internal round-trip tests; confirm grammar fidelity against the authoritative
ANTLR parser once by loading an emitted file into the `dsl` module's
`CypherDryRunRepl`:

```bash
python examples/synthetic_end_to_end.py
# then load pipeline/out/synthetic.causal in the dsl module's CypherDryRunRepl
```

If it parses clean, the output contract is fully verified.

**4. Run on your own data (Mode B).** Copy `examples/covid.yaml`, point
`data.path` at a CSV whose column names match your DAG's variable ids, point
`dag.path` at a GML/DOT/edge-list file, then:

```bash
causal4j-pipeline run yourconfig.yaml
```

Output lands in `out/<name>.causal` + `out/<name>.provenance.json`. Load the
`.causal` file with the DSL REPL — the pipeline never writes to the database.

**5. Try discovery (Mode A).** With `[discovery]` installed, adapt
`examples/discovery.yaml` to your data and run it; inspect the provenance
sidecar for the algorithm used and any heuristic orientation decisions.

### Notes / current limitations

- causal-learn and dowhy are optional; without them the discovery adapters and
  the dowhy refuters (`placebo`, `random_common_cause`, `subset`) report as
  skipped rather than failing. The always-available refuters (`ci`, `gof`) need
  only numpy.
- The ANTLR round-trip (step 3) is the definitive grammar check and should be
  run once on your machine; the built-in tests use the package's own reader.
