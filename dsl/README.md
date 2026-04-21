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
3. [Structural queries](#structural-queries) — parents, children, ancestors, descendants, mediators, colliders, confounders, backdoor
4. [Identification](#identification) — `IDENTIFY EFFECT OF … ON …`
5. [Estimation](#estimation) — `ESTIMATE EFFECT OF … ON …` *(v1 stub)*
6. [Intervention & counterfactual](#intervention--counterfactual) *(v1 stubs)*
7. [Inspection](#inspection) — `SHOW DAG`, `DESCRIBE VARIABLE`
8. [Grammar](#grammar)
9. [Full example](#full-example)

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

**Example**

```
DEFINE CAUSAL VARIABLE Age FOR UNIT User TYPE continuous ROLE covariate
```

### `DEFINE CAUSAL DAG`

```
DEFINE CAUSAL DAG <name> {
    <from> -> <to>
    ...
}
```

Declares a set of causal edges. Endpoints that were not previously defined are
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
    VARIABLE <name> [FOR UNIT ...] [OBSERVED AS ...] [TYPE ...] [ROLE ...]
    ...
    DAG {
        <from> -> <to>
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

## Intervention & counterfactual

> Both are **v1 parse-only stubs** — the AST is built but execution is not
> implemented. They are listed here so the syntax is stable for future work.

### `INTERVENE`

```
[FROM MODEL <model>]
INTERVENE ON <var> = <value>
    [COMPARE TO <var> = <value>]
    RETURN (DISTRIBUTION OF <V> | EFFECT ON <V> | EXPECTATION OF <V>)
```

Do-calculus intervention: `do(X = x)`. `COMPARE TO` computes a contrast against
a second intervention (for ATE-like quantities).

### `COUNTERFACTUAL`

```
COUNTERFACTUAL [FOR <unit>("<id>")]
    [GIVEN <var> = <value>, ...]
    WHAT WOULD <outcome> HAVE BEEN IF <var> = <value>
```

Unit-level counterfactual: given observed factual values (`GIVEN …`), what
would the outcome have been under the stated intervention.

---

## Inspection

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
                    | showDagStmt
                    | describeVariableStmt ;

defineVariableStmt  = 'DEFINE' 'CAUSAL' 'VARIABLE' ID
                      [ 'FOR' 'UNIT' ID ]
                      [ 'OBSERVED' 'AS' STRING ]
                      [ 'TYPE' variableType ]
                      [ 'ROLE' variableRole ] ;

defineModelStmt     = 'DEFINE' 'MODEL' ID '{' variableDecl* dagBlock '}'
                    | 'DEFINE' 'CAUSAL' 'DAG' ID '{' edge+ '}' ;

variableDecl        = 'VARIABLE' ID [ 'FOR' 'UNIT' ID ]
                      [ 'OBSERVED' 'AS' STRING ]
                      [ 'TYPE' variableType ] [ 'ROLE' variableRole ] ;

dagBlock            = 'DAG' '{' edge+ '}' ;
edge                = ID '->' ID ;

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

assignment          = ID '=' literal ;
literal             = NUMBER | STRING | BOOL ;

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
