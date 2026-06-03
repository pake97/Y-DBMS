# Causal DSL — Language Reference

A small declarative language for describing causal models and asking causal
questions about them. Programs are a sequence of statements terminated by
whitespace; there is no statement separator.

This document specifies the **language** only. For the Java API, the two
REPLs, and how programs are executed against Neo4j, see
[`../README.md`](../README.md).


---

## Table of contents

1. [Lexical structure](#lexical-structure)
2. [Schema statements](#schema-statements) — `DEFINE CAUSAL VARIABLE`, `DEFINE CAUSAL DAG`, `DEFINE MODEL`
3. [Linear-Gaussian parameters](#linear-gaussian-parameters) — `WEIGHT`, `MEAN`, `NOISE`
4. [Structural queries](#structural-queries) — parents, children, ancestors, descendants, mediators, colliders, confounders, backdoor
5. [Identification](#identification) — `IDENTIFY EFFECT OF … ON …`
6. [Estimation](#estimation) — `ESTIMATE EFFECT OF … ON …` *(v1 stub)*
7. [Intervention & context switching](#intervention--context-switching) — `INTERVENE`, `USE MODEL`, `RESET`
8. [Counterfactual](#counterfactual) — `COUNTERFACTUAL … WHAT WOULD … HAVE BEEN IF …`
9. [Inspection](#inspection) — `SHOW MODELS`, `SHOW DAG`, `DESCRIBE VARIABLE`
10. [Grammar](#grammar)
11. [Full example](#full-example)

---

## Lexical structure

### Case conventions

| Token class | Case | Example |
|---|---|---|
| Statement keywords | **UPPERCASE** | `DEFINE`, `CAUSES`, `IDENTIFY` |
| Variable types / roles | **lowercase** | `binary`, `treatment` |
| Identifiers (variable / model names) | any letter + digits + `_`, cannot start with a digit | `Treatment`, `Retention_30` |

Keywords and identifiers live in disjoint token classes, so an identifier
`model` (lowercase) would be read as the `MODEL` keyword — **do not use
lowercase reserved words as identifier names.**

### Literals

- `NUMBER`: optional `-`, digits, optional fractional part. `42`, `-3.14`.
- `STRING`: double-quoted, no line breaks, `\"` escapes a quote. `"hello \"world\""`.
- `BOOL`: `true` | `false`.

### Whitespace & comments

- Whitespace (spaces, tabs, newlines) separates tokens and is otherwise ignored.
- `// line comment`
- `/* block comment */`

---

## Schema statements

The schema layer defines **what** the variables are and **how** they connect.
Every DSL program starts with schema; queries reference the names introduced here.

### `DEFINE CAUSAL VARIABLE`

```
DEFINE CAUSAL VARIABLE <name>
    [FOR UNIT <unit>]
    [OBSERVED AS "<cypher>"]
    [TYPE <variableType>]
    [ROLE <variableRole>]
    [MEAN <number>]
    [NOISE <number>]
```

Registers a single variable.

- `FOR UNIT <unit>` — the entity each observation is keyed by (e.g. `User`, `Patient`).
  Used by `ESTIMATE` to join observational data.
- `OBSERVED AS "<cypher>"` — a Cypher subquery that returns the observed
  values for this variable. Must yield one row per unit. **Stored but not yet
  executed** (see [Estimation](#estimation)).
- `TYPE` — one of `binary`, `continuous`, `categorical`, `event`, `latent`.
  `latent` marks a variable that is not directly observed; all others are observed.
- `ROLE` — one of `treatment`, `outcome`, `covariate`, `mediator`, `collider`,
  `confounder`, `unknown`. Hint for planners; does not constrain the graph.
- `MEAN` / `NOISE` — linear-Gaussian parameters (intercept and noise variance);
  see [Linear-Gaussian parameters](#linear-gaussian-parameters). Honored when the
  variable is declared inside a `DEFINE MODEL` block; on a standalone
  `DEFINE CAUSAL VARIABLE` they are accepted but ignored in v1.

**Example**

```
DEFINE CAUSAL VARIABLE Age FOR UNIT User TYPE continuous ROLE covariate
```

### `DEFINE CAUSAL DAG`

```
DEFINE CAUSAL DAG <name> {
    <from> -> <to> [WEIGHT <number>]
    ...
}
```

Declares a set of causal edges. The optional `WEIGHT` is the linear-Gaussian
structural coefficient β for that edge (see
[Linear-Gaussian parameters](#linear-gaussian-parameters)). Endpoints that were not previously defined are
auto-registered as observed variables with no type / role. Prefer
[`DEFINE MODEL`](#define-model) when you want to declare variable metadata and
the graph together.

**Example**

```
DEFINE CAUSAL DAG Retention {
    Age        -> Treatment
    Treatment  -> Retention30
    Engagement -> Retention30
}
```

### `DEFINE MODEL`

```
DEFINE MODEL <name> {
    VARIABLE <name> [FOR UNIT ...] [OBSERVED AS ...] [TYPE ...] [ROLE ...] [MEAN ...] [NOISE ...]
    ...
    DAG {
        <from> -> <to> [WEIGHT <number>]
        ...
    }
}
```

Block form: variable declarations **and** the DAG in one statement. The most
common way to bootstrap a model. Variable declarations inside the block use
the bare keyword `VARIABLE` (not `DEFINE CAUSAL VARIABLE`).

**Example**

```
DEFINE MODEL UserRetentionModel {
    VARIABLE Treatment    FOR UNIT User TYPE binary     ROLE treatment
    VARIABLE Retention30  FOR UNIT User TYPE binary     ROLE outcome
    VARIABLE Age          FOR UNIT User TYPE continuous ROLE covariate
    VARIABLE Engagement   FOR UNIT User TYPE continuous ROLE mediator

    DAG {
        Age       -> Treatment
        Age       -> Retention30
        Treatment -> Engagement
        Treatment -> Retention30
        Engagement -> Retention30
    }
}
```

---

## Linear-Gaussian parameters

Edges and variables may carry **linear-Gaussian structural-equation parameters**.
With them, a model is no longer just a structural DAG — each variable is

```
V = intercept_V  +  Σ_{P ∈ parents(V)} β_{P→V} · P  +  ε_V ,   ε_V ~ N(0, σ²_V)
```

| Clause | Where | Meaning | Default |
|---|---|---|---|
| `WEIGHT <number>` | on an edge `A -> B` | coefficient β for `A → B` | `1.0` |
| `MEAN <number>` | on a variable | intercept (structural constant) | `0.0` |
| `NOISE <number>` | on a variable | noise variance σ² | `1.0` |

A model that declares **any** of these is *parameterized*; one that declares none
stays *structural-only*. Parameters power numeric answers from
[`INTERVENE`](#intervention--context-switching) (`EXPECTATION`, `DISTRIBUTION`,
`EFFECT`); a structural-only model answers `INTERVENE` qualitatively instead.

`MEAN` / `NOISE` are honored on variables declared inside a `DEFINE MODEL` block
(and default otherwise). `WEIGHT` is honored on edges in either DAG form.

> **Scope:** numeric evaluation runs on the **in-memory** executor. Against the
> Neo4j backend the linear-Gaussian engine is not yet wired (it is a plugin
> exercise), so `INTERVENE` there returns the structural answer.

**Example**

```
DEFINE MODEL Chain {
    VARIABLE X MEAN 0 NOISE 1
    VARIABLE M MEAN 0 NOISE 1
    VARIABLE Y MEAN 0 NOISE 1
    DAG {
        X -> M WEIGHT 2
        M -> Y WEIGHT 3
    }
}
```

Here the total effect of `X` on `Y` is `2 · 3 = 6`.

---

## Structural queries

All structural queries return a **set of variable names** from the compiled DAG.

| Statement | Returns |
|---|---|
| `CAUSES OF <V>` | direct parents of `V` |
| `EFFECTS OF <V>` | direct children of `V` |
| `ANCESTORS OF <V>` | all ancestors of `V` (transitive, excluding `V`) |
| `DESCENDANTS OF <V>` | all descendants of `V` (transitive, excluding `V`) |
| `MEDIATORS BETWEEN <A> AND <B>` | variables on at least one directed path `A → … → B` |
| `COLLIDERS BETWEEN <A> AND <B>` | variables `Z` where effects from `A` and `B` meet head-to-head `A → Z ← B` |
| `CONFOUNDERS OF <A> AND <B>` | common ancestors of `A` and `B` |
| `BACKDOOR PATHS BETWEEN <T> AND <Y>` | variables on at least one backdoor path from `T` to `Y` (path entering `T` via an incoming edge, avoiding descendants of `T`) |

**Example**

```
CAUSES OF Retention30
EFFECTS OF Treatment
MEDIATORS BETWEEN Treatment AND Retention30
CONFOUNDERS OF Treatment AND Retention30
BACKDOOR PATHS BETWEEN Treatment AND Retention30
```

---

## Identification

```
IDENTIFY EFFECT OF <treatment> ON <outcome>
    [RETURN ADJUSTMENT SETS]
    [EXPLAIN]
```

Decides whether the causal effect `T → Y` is identifiable from observational
data by the backdoor criterion, and (with `RETURN ADJUSTMENT SETS`) returns
candidate adjustment sets.

- `RETURN ADJUSTMENT SETS` — include the list of valid `Z` sets in the result.
- `EXPLAIN` — include a human-readable explanation (e.g. which backdoor path
  each set closes).

**Example**

```
IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS EXPLAIN
```

**Result**

```
IDENTIFY EFFECT OF Treatment ON Retention30
  identifiable: true
  method:       backdoor adjustment
  adjustment sets: [{Age, PriorUsage}]
```

---

## Estimation

```
ESTIMATE EFFECT OF <treatment> ON <outcome>
    [ADJUST FOR [<var1>, <var2>, ...]]
    [USING <estimator>]
```

Estimates the average causal effect from observational data. `ADJUST FOR`
specifies the adjustment set; `USING` picks an estimator identifier
(`aipw`, `ipw`, `g_formula`, …).

> **v1 status:** the statement parses and validates the adjustment set
> (rejecting sets that contain descendants of the treatment), but numeric
> estimation is not wired yet — it requires executing the `OBSERVED AS`
> Cypher bindings. Calling it on the Neo4j backend returns a placeholder.

**Example**

```
ESTIMATE EFFECT OF Treatment ON Retention30 ADJUST FOR [Age, PriorUsage] USING aipw
```

**Validation error example**

```
ESTIMATE EFFECT OF Treatment ON Retention30 ADJUST FOR [Engagement]
→ error: adjustment set contains descendants of Treatment: [Engagement].
         Conditioning on descendants of treatment opens collider bias.
```

---

## Intervention & context switching

### `INTERVENE`

```
[FROM MODEL <model>]
INTERVENE ON <var> = <value>
    [COMPARE TO <var> = <value>]
    RETURN (DISTRIBUTION OF <V> | EFFECT ON <V> | EXPECTATION OF <V>)
```

The do-operator `do(X = x)`. Execution is a **graph transformation plus a context
switch**:

1. A new **derived world** is created by graph surgery — every edge *into* `X` is
   removed and `X` is fixed to `value`. The original model is left untouched.
2. The derived world is registered under an ID-safe name
   `<model>_do_<var>_<value>` and becomes the **active graph**. Every subsequent
   statement runs against it until you [`USE MODEL`](#use-model) or
   [`RESET`](#reset).
3. The `RETURN` clause is answered against the derived world.

`FROM MODEL` derives from a named model instead of the active one. `COMPARE TO`
contrasts against a second intervention value.

**What `RETURN` yields** depends on whether the model is
[parameterized](#linear-gaussian-parameters):

| | Structural-only model | Linear-Gaussian model |
|---|---|---|
| `EFFECT ON Y` | does `do(X)` reach `Y`? + the causal pathway | total causal effect coefficient |
| `EXPECTATION OF Y` | structural note | `E[Y \| do(X=x)]` |
| `DISTRIBUTION OF Y` | structural note | mean **and** variance of `Y` |
| `COMPARE TO` | "identical structure" note | numeric contrast `Δ` |

**Example** (against the parameterized `Chain` model above)

```
INTERVENE ON X = 1 RETURN EFFECT ON Y          // total effect = 6
INTERVENE ON X = 4 RETURN EXPECTATION OF Y      // E[Y | do(X=4)] = 24
INTERVENE ON X = 1 RETURN DISTRIBUTION OF Y     // mean 6, variance 10
INTERVENE ON X = 1 COMPARE TO X = 0 RETURN EFFECT ON Y   // Δ = 6
```

### `USE MODEL`

```
USE MODEL <name>
```

Switch the active graph to a registered model — a base model or a derived
intervention world (e.g. `Chain_do_X_1`). Lets you move between worlds and
compare queries across them.

### `RESET`

```
RESET
```

Reset the active graph back to the base (original) model, discarding the active
intervention world. Derived worlds stay registered and remain reachable via
`USE MODEL`.

---

## Counterfactual

```
COUNTERFACTUAL [FOR <unit>("<id>")]
    [GIVEN <var> = <value>, ...]
    WHAT WOULD <outcome> HAVE BEEN IF <var> = <value>
```

Unit-level counterfactual: given the factual world (`GIVEN …`), what would the
outcome have been under the stated hypothetical. On a
[linear-Gaussian model](#linear-gaussian-parameters) this is closed-form —
holding every noise term fixed at its factual value, the outcome is affine in the
intervened variable:

```
Y_cf = Y_factual + totalEffect(X → Y) · (x* − X_factual)
```

so confounders and everything off the `X → Y` paths stay at their factual
contribution. `GIVEN` must supply the numeric factual values of the intervened
variable and the outcome. A structural-only model (no declared parameters)
returns an explanatory note instead of a number.

**Example** (on the parameterized `Chain` model, total effect `X→Y = 6`)

```
COUNTERFACTUAL GIVEN X = 1, Y = 10 WHAT WOULD Y HAVE BEEN IF X = 3
// factual Y = 10, counterfactual Y = 22, difference = 12
```

> **Scope:** the numeric evaluation runs on the **in-memory** executor (same as
> numeric `INTERVENE`); the Neo4j-backed engine is a plugin exercise.

---

## Inspection

### `SHOW MODELS`

```
SHOW MODELS
```

Lists every registered model — base models and derived intervention worlds (e.g.
`Chain_do_X_1`) — marking which one is the **active graph** with `*`. Pairs with
[`INTERVENE`](#intervention--context-switching) / [`USE MODEL`](#use-model) /
[`RESET`](#reset) so you can see and navigate the worlds you have created.

### `SHOW DAG`

```
SHOW DAG [<name>]
```

Prints the currently loaded DAG: variable list + edges. If `<name>` is
omitted, shows the active DAG. Useful inside the interactive REPL.

### `DESCRIBE VARIABLE`

```
DESCRIBE VARIABLE <name>
```

Prints everything the runtime knows about a variable: unit, type, role,
observed/latent flag, and its `OBSERVED AS` binding.

---

## Grammar

The authoritative grammar lives at
[`src/main/antlr4/com/example/causal/dsl/parser/CausalDSL.g4`](src/main/antlr4/com/example/causal/dsl/parser/CausalDSL.g4).
In EBNF-ish shorthand:

```ebnf
program             = statement* EOF ;

statement           = defineVariableStmt
                    | defineModelStmt         (* block MODEL or DAG *)
                    | structuralQuery
                    | identifyStmt
                    | estimateStmt
                    | interveneStmt
                    | counterfactualStmt
                    | useModelStmt
                    | resetStmt
                    | showModelsStmt
                    | showDagStmt
                    | describeVariableStmt ;

defineVariableStmt  = 'DEFINE' 'CAUSAL' 'VARIABLE' ID
                      [ 'FOR' 'UNIT' ID ]
                      [ 'OBSERVED' 'AS' STRING ]
                      [ 'TYPE' variableType ]
                      [ 'ROLE' variableRole ]
                      [ 'MEAN' NUMBER ]
                      [ 'NOISE' NUMBER ] ;

defineModelStmt     = 'DEFINE' 'MODEL' ID '{' variableDecl* dagBlock '}'
                    | 'DEFINE' 'CAUSAL' 'DAG' ID '{' edge+ '}' ;

variableDecl        = 'VARIABLE' ID [ 'FOR' 'UNIT' ID ]
                      [ 'OBSERVED' 'AS' STRING ]
                      [ 'TYPE' variableType ] [ 'ROLE' variableRole ]
                      [ 'MEAN' NUMBER ] [ 'NOISE' NUMBER ] ;

dagBlock            = 'DAG' '{' edge+ '}' ;
edge                = ID '->' ID [ 'WEIGHT' NUMBER ] ;

structuralQuery     = 'CAUSES' 'OF' ID
                    | 'EFFECTS' 'OF' ID
                    | 'ANCESTORS' 'OF' ID
                    | 'DESCENDANTS' 'OF' ID
                    | 'MEDIATORS' 'BETWEEN' ID 'AND' ID
                    | 'COLLIDERS' 'BETWEEN' ID 'AND' ID
                    | 'CONFOUNDERS' 'OF' ID 'AND' ID
                    | 'BACKDOOR' 'PATHS' 'BETWEEN' ID 'AND' ID ;

identifyStmt        = 'IDENTIFY' 'EFFECT' 'OF' ID 'ON' ID
                      [ 'RETURN' 'ADJUSTMENT' 'SETS' ] [ 'EXPLAIN' ] ;

estimateStmt        = 'ESTIMATE' 'EFFECT' 'OF' ID 'ON' ID
                      [ 'ADJUST' 'FOR' '[' ID (',' ID)* ']' ]
                      [ 'USING' ID ] ;

interveneStmt       = [ 'FROM' 'MODEL' ID ]
                      'INTERVENE' 'ON' assignment
                      [ 'COMPARE' 'TO' assignment ]
                      'RETURN' ( 'DISTRIBUTION' 'OF' ID
                               | 'EFFECT' 'ON' ID
                               | 'EXPECTATION' 'OF' ID ) ;

counterfactualStmt  = 'COUNTERFACTUAL' [ 'FOR' ID '(' STRING ')' ]
                      [ 'GIVEN' assignment (',' assignment)* ]
                      'WHAT' 'WOULD' ID 'HAVE' 'BEEN' 'IF' assignment ;

useModelStmt        = 'USE' 'MODEL' ID ;
resetStmt           = 'RESET' ;

assignment          = ID '=' literal ;
literal             = NUMBER | STRING | BOOL ;

showModelsStmt      = 'SHOW' 'MODELS' ;
showDagStmt         = 'SHOW' 'DAG' [ ID ] ;
describeVariableStmt= 'DESCRIBE' 'VARIABLE' ID ;

variableType        = 'binary' | 'continuous' | 'categorical' | 'event' | 'latent' ;
variableRole        = 'treatment' | 'outcome' | 'covariate' | 'mediator'
                    | 'collider'  | 'confounder' | 'unknown' ;
```

---

## Full example

See [`../dev/example.causal`](../dev/example.causal) for a runnable script that
exercises every category of statement. Abridged:

```
DEFINE CAUSAL VARIABLE Treatment   FOR UNIT User TYPE binary      ROLE treatment
DEFINE CAUSAL VARIABLE Retention30 FOR UNIT User TYPE binary      ROLE outcome
DEFINE CAUSAL VARIABLE Age         FOR UNIT User TYPE continuous  ROLE covariate
DEFINE CAUSAL VARIABLE PriorUsage  FOR UNIT User TYPE continuous  ROLE covariate
DEFINE CAUSAL VARIABLE Engagement  FOR UNIT User TYPE continuous  ROLE mediator

DEFINE CAUSAL DAG UserRetentionModel {
    Age        -> Treatment
    Age        -> Retention30
    PriorUsage -> Treatment
    PriorUsage -> Retention30
    Treatment  -> Engagement
    Treatment  -> Retention30
    Engagement -> Retention30
}

CAUSES OF Retention30
MEDIATORS BETWEEN Treatment AND Retention30
CONFOUNDERS OF Treatment AND Retention30

IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS
ESTIMATE  EFFECT OF Treatment ON Retention30 ADJUST FOR [Age, PriorUsage] USING aipw
```
