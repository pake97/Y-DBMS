"""Reference answers (RQ5 oracle, WP2 fixture) from dagitty and pgmpy.

d-separation: pgmpy always, dagitty when R + dagitty are importable through rpy2;
the two must agree. Adjustment: canonical set from the definition (Perković et
al. 2018) checked with pgmpy in the proper backdoor graph, and canonical +
minimal sets from dagitty; the two canonical sets must agree.
"""
import multiprocessing as mp

import networkx as nx

# Minimal-set families can be exponential (one andes pair has 6404 sets), and R's
# setTimeLimit cannot interrupt dagitty's V8 engine, so dagitty runs in a worker
# process that is killed after PER_PAIR_SECONDS. A family larger than MAX_FAMILY
# is recorded by size only (minimal = null, minimal_count = size).
PER_PAIR_SECONDS = 30
MAX_FAMILY = 1000
TIMEOUT = "timeout"


# ── pure Python / pgmpy ─────────────────────────────────────────────────────

def _dag(edges):
    from pgmpy.base import DAG
    return DAG(edges)


def separated(dag, x, y, z) -> bool:
    return not dag.is_dconnected(x, y, observed=list(z))


def canonical(g: nx.DiGraph, t, y, latent=frozenset()):
    """Canonical adjustment set for the total effect of t on y, or None if not identifiable.

    cn = (de(t) ∩ an(y)) ∪ {y}, forb = de(cn) ∪ cn ∪ {t},
    Z = an({t, y}) \\ (forb ∪ {t, y} ∪ latent); valid iff Z separates t and y
    in the proper backdoor graph (t's edges into cn removed).
    """
    cn = (nx.descendants(g, t) & nx.ancestors(g, y)) | {y}
    forb = {t} | cn
    for c in cn:
        forb |= nx.descendants(g, c)
    z = (nx.ancestors(g, t) | nx.ancestors(g, y)) - forb - {t, y} - set(latent)
    pbd = [(a, b) for a, b in g.edges() if not (a == t and b in cn)]
    dag = _dag(pbd)
    dag.add_nodes_from(g.nodes)
    return z if separated(dag, t, y, z) else None


# ── dagitty through rpy2 ────────────────────────────────────────────────────

class Dagitty:
    """One dagitty graph per dataset; node ids travel as aliases n0..nK."""

    def __init__(self, g: nx.DiGraph, latent=frozenset()):
        import rpy2.robjects as ro
        self.ro = ro
        self.alias = {v: f"n{i}" for i, v in enumerate(g.nodes)}
        self.back = {a: v for v, a in self.alias.items()}
        decl = "; ".join(f"{self.alias[v]} [latent]" for v in g.nodes if v in latent)
        body = "; ".join(f"{self.alias[a]} -> {self.alias[b]}" for a, b in g.edges())
        nodes = "; ".join(self.alias[v] for v in g.nodes)
        self.g = ro.r("dagitty::dagitty")(f"dag {{ {nodes}; {decl}; {body} }}")
        self._dsep = ro.r("function(g, x, y, z) dagitty::dseparated(g, x, y, z)")
        self._adj = ro.r("""
        function(g, t, y, type) {
          s <- tryCatch(dagitty::adjustmentSets(g, t, y, type = type, effect = "total"),
                        error = function(e) NULL)
          if (is.null(s)) return(NULL)
          lapply(s, as.character)
        }""")

    def separated(self, x, y, z) -> bool:
        zs = self.ro.StrVector([self.alias[v] for v in z])
        return bool(self._dsep(self.g, self.alias[x], self.alias[y], zs)[0])

    def sets(self, t, y, kind):
        """List of sets (ids), or None on error."""
        r = self._adj(self.g, self.alias[t], self.alias[y], kind)
        if r is self.ro.NULL:
            return None
        return [{self.back[a] for a in s} for s in r]


def _serve(conn, nodes, edges, latent):
    g = nx.DiGraph()
    g.add_nodes_from(nodes)
    g.add_edges_from(edges)
    dg = Dagitty(g, latent)
    while (req := conn.recv()) is not None:
        op, *args = req
        if op == "dsep":
            conn.send(dg.separated(*args))
        else:
            fam = dg.sets(*args)
            conn.send(("too_many", len(fam)) if fam is not None and len(fam) > MAX_FAMILY else fam)


class DagittyWorker:
    """Dagitty in a child process, so a runaway enumeration can be killed."""

    def __init__(self, g: nx.DiGraph, latent=frozenset()):
        self.args = (list(g.nodes), list(g.edges), frozenset(latent))
        self.restarts = 0
        self._start()

    def _start(self):
        ctx = mp.get_context("spawn")
        self.conn, child = ctx.Pipe()
        self.proc = ctx.Process(target=_serve, args=(child, *self.args), daemon=True)
        self.proc.start()

    def _call(self, req, timeout):
        self.conn.send(req)
        if self.conn.poll(timeout):
            return self.conn.recv()
        self.proc.kill()
        self.proc.join()
        self.restarts += 1
        self._start()
        return TIMEOUT

    def separated(self, x, y, z):
        r = self._call(("dsep", x, y, list(z)), timeout=600)
        if r == TIMEOUT:
            raise RuntimeError(f"dagitty dseparated timed out on {x}, {y} | {z}")
        return r

    def sets(self, t, y, kind, timeout=PER_PAIR_SECONDS):
        """List of sets, None on error, TIMEOUT, or ("too_many", size)."""
        return self._call(("sets", t, y, kind), timeout)

    def close(self):
        self.conn.send(None)
        self.proc.join(5)
        if self.proc.is_alive():
            self.proc.kill()


def dagitty_version():
    try:
        import rpy2.robjects as ro
        return str(ro.r('as.character(packageVersion("dagitty"))')[0])
    except Exception:  # no R, no rpy2, or no dagitty package
        return None


# ── per-dataset computation ─────────────────────────────────────────────────

def dsep_rows(dag, dg, triples):
    """{x, y, z, pgmpy, dagitty} per triple (dagitty None without a worker); the two must agree."""
    rows = []
    for x, y, z in triples:
        p = separated(dag, x, y, z)
        d = dg.separated(x, y, z) if dg else None
        if d is not None and d != p:
            raise AssertionError(f"d-separation disagreement on {x}, {y} | {z}: pgmpy {p}, dagitty {d}")
        rows.append({"x": x, "y": y, "z": list(z), "pgmpy": p, "dagitty": d})
    return rows


def balanced_dsep(g: nx.DiGraph, triples, latent=frozenset(), use_dagitty=True):
    """dsep_rows for triples_balanced.csv (RQ5, WP5b-2)."""
    dag = _dag(g.edges())
    dag.add_nodes_from(g.nodes)
    dg = DagittyWorker(g, latent) if use_dagitty else None
    try:
        return dsep_rows(dag, dg, triples)
    finally:
        if dg:
            dg.close()


# ── RQ5: validation of sets Y-DBMS streamed for pairs without a reference family ──

VALID_MINIMAL, VALID_NOT_MINIMAL, INVALID = 2, 1, 0
_validators = {}


def _validate_pair(task):
    """(dataset, dataset dir, index, t, y, sets) -> (dataset, index, codes): per set INVALID unless
    dagitty's isAdjustmentSet accepts it, VALID_NOT_MINIMAL if it also accepts a one-smaller subset."""
    from .io import read_dataset, read_json
    ds, d, i, t, y, sets = task
    if ds not in _validators:
        latent = frozenset(read_json(d / "meta.json").get("latent", []))
        dg = Dagitty(read_dataset(d), latent)
        dg.check = dg.ro.r("""
        function(g, t, y, sets) vapply(sets, function(z) {
          if (!dagitty::isAdjustmentSet(g, z, t, y)) return(0L)
          for (v in z) if (dagitty::isAdjustmentSet(g, setdiff(z, v), t, y)) return(1L)
          2L }, integer(1))""")
        _validators.clear()  # one graph per worker at a time
        _validators[ds] = dg
    dg = _validators[ds]
    ro = dg.ro
    rsets = ro.ListVector([(str(k), ro.StrVector([dg.alias[v] for v in s])) for k, s in enumerate(sets)])
    return ds, i, list(dg.check(dg.g, dg.alias[t], dg.alias[y], rsets))


def validate(todo, dirs, workers):
    """todo: {dataset: [{t, y, identifiable, sets}]}; dirs: dataset -> dataset dir.
    Returns {dataset: [codes per pair, aligned with todo]}, run on `workers` processes."""
    tasks = [(ds, dirs[ds], i, p["t"], p["y"], p["sets"]) for ds, ps in todo.items()
             for i, p in enumerate(ps) if p["sets"]]
    out = {ds: [[] for _ in ps] for ds, ps in todo.items()}
    with mp.get_context("spawn").Pool(workers) as pool:
        for k, (ds, i, codes) in enumerate(pool.imap_unordered(_validate_pair, tasks), 1):
            out[ds][i] = codes
            if k % 50 == 0 or k == len(tasks):
                print(f"validate: {k}/{len(tasks)} pairs", flush=True)
    return out


def compute(g: nx.DiGraph, pairs, triples, latent=frozenset(), use_dagitty=True, with_minimal=True):
    """Returns (dsep_rows, adjustment_rows, counters); with_minimal=False skips minimal families."""
    order = {v: i for i, v in enumerate(g.nodes)}
    ordered = lambda s: sorted(s, key=order.get)  # noqa: E731
    dag = _dag(g.edges())
    dag.add_nodes_from(g.nodes)
    dg = DagittyWorker(g, latent) if use_dagitty else None
    stats = {"minimal_timeouts": 0, "minimal_too_many": 0}

    dsep = dsep_rows(dag, dg, triples)

    adjustment = []
    for t, y in pairs:
        can = canonical(g, t, y, latent)
        minimal = count = None
        if dg:
            dc = dg.sets(t, y, "canonical", timeout=600)
            if dc == TIMEOUT or dc is None:
                raise RuntimeError(f"dagitty canonical set failed on ({t}, {y})")
            dcan = dc[0] if dc else None
            if dcan != can:
                raise AssertionError(f"canonical disagreement on ({t}, {y}): python {can}, dagitty {dcan}")
            fam = dg.sets(t, y, "minimal") if with_minimal else "skipped"
            if fam == "skipped":
                pass
            elif fam == TIMEOUT:
                stats["minimal_timeouts"] += 1
            elif isinstance(fam, tuple):
                stats["minimal_too_many"] += 1
                count = fam[1]
            elif fam is None:
                raise RuntimeError(f"dagitty minimal sets failed on ({t}, {y})")
            else:
                minimal = sorted((ordered(s) for s in fam), key=lambda s: [order[v] for v in s])
                count = len(minimal)
                if bool(minimal) != (can is not None):
                    raise AssertionError(f"identifiability disagreement on ({t}, {y})")
        adjustment.append({
            "t": t, "y": y,
            "canonical": ordered(can) if can is not None else None,
            "minimal": minimal,
            "minimal_count": count,
            "identifiable": can is not None,
        })
    if dg:
        stats["dagitty_restarts"] = dg.restarts
        dg.close()
    return dsep, adjustment, stats
