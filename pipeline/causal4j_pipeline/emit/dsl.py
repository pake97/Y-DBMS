"""Serializer: CausalModel -> grammar-valid ``.causal`` text.

Targets the grammar in dsl/src/main/antlr4/com/example/causal/dsl/parser/CausalDSL.g4
exactly. Two constraints worth calling out:

* The DSL NUMBER token is ``'-'? [0-9]+ ('.' [0-9]+)?`` — **no scientific
  notation** — so all floats are rendered as plain decimals
  (:func:`format_number`).
* ``DEFINE MODEL`` requires a DAG block with at least one edge; the caller
  must validate the model first (``CausalModel.validate``).

Output is deterministic: variables in topological order (lexicographic
tie-break), edges sorted by (source, target). Same model in, byte-identical
file out.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from causal4j_pipeline.model import CausalModel, VariableSpec


def format_number(x: float, precision: int = 6) -> str:
    """Render a float as a DSL-safe plain decimal (never scientific notation)."""
    if x != x or x in (float("inf"), float("-inf")):
        raise ValueError(f"Cannot emit non-finite number {x!r} into the DSL")
    if float(x) == int(x) and abs(x) < 1e15:
        return str(int(x))
    s = f"{x:.{precision}f}"
    s = s.rstrip("0").rstrip(".")
    if s in ("", "-", "-0"):
        s = "0"
    return s


def _variable_line(v: VariableSpec, default_unit: Optional[str], indent: str = "  ") -> str:
    # Option order must follow the grammar: FOR UNIT, OBSERVED AS, TYPE, ROLE, MEAN, NOISE.
    parts = [f"{indent}VARIABLE {v.name}"]
    unit = v.unit or default_unit
    if unit:
        parts.append(f"FOR UNIT {unit}")
    if v.observed_as:
        escaped = v.observed_as.replace('"', '\\"')
        parts.append(f'OBSERVED AS "{escaped}"')
    if v.var_type:
        parts.append(f"TYPE {v.var_type}")
    if v.role:
        parts.append(f"ROLE {v.role}")
    if v.mean is not None and not v.latent:
        parts.append(f"MEAN {format_number(v.mean)}")
    if v.noise is not None and not v.latent:
        parts.append(f"NOISE {format_number(v.noise)}")
    return " ".join(parts)


def emit_dsl(
    model: CausalModel,
    default_unit: Optional[str] = None,
    header: Optional[str] = None,
) -> str:
    """Serialize a validated CausalModel to ``.causal`` text."""
    model.validate()

    lines = []
    if header:
        for h in header.splitlines():
            lines.append(f"// {h}".rstrip())
        lines.append("")

    lines.append(f"DEFINE MODEL {model.name} {{")

    order = model.topo_order()
    # Variables not in the graph topo order (isolated) go last, sorted.
    remaining = sorted(set(model.variables) - set(order))
    for name in list(order) + remaining:
        lines.append(_variable_line(model.variables[name], default_unit))

    lines.append("  DAG {")
    width = max(len(e.source) for e in model.edges)
    for e in sorted(model.edges, key=lambda e: (e.source, e.target)):
        line = f"    {e.source:<{width}} -> {e.target}"
        if e.weight is not None:
            line += f"  WEIGHT {format_number(e.weight)}"
        lines.append(line)
    lines.append("  }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def write_dsl(
    model: CausalModel,
    path: str | Path,
    default_unit: Optional[str] = None,
    header: Optional[str] = None,
) -> Path:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(emit_dsl(model, default_unit=default_unit, header=header))
    return out
