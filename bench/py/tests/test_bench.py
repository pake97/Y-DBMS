import networkx as nx
import pytest

from causal4j_bench import DATASETS, bif, reference, synthetic, workloads
from causal4j_bench.io import read_dataset, write_edges

MINI_BIF = """
network unknown { }
variable a { type discrete [ 2 ] { yes, no }; }
variable b { type discrete [ 2 ] { yes, no }; }
variable c { type discrete [ 2 ] { yes, no }; }
probability ( a ) { table 0.5, 0.5; }
probability ( b | a ) { (yes) 0.1, 0.9; (no) 0.2, 0.8; }
probability ( c | a, b ) { (yes, yes) 0.1, 0.9; (no, yes) 0.1, 0.9; (yes, no) 0.1, 0.9; (no, no) 0.1, 0.9; }
"""


# ── bif ─────────────────────────────────────────────────────────────────────

def test_bif_parses_roots_and_parent_lists():
    g = bif.parse(MINI_BIF)
    assert list(g.nodes) == ["a", "b", "c"]
    assert set(g.edges) == {("a", "b"), ("a", "c"), ("b", "c")}


def test_bif_asia():
    path = DATASETS / "bnlearn" / "asia" / "asia.bif.gz"
    if not path.exists():
        pytest.skip("run bench/datasets/fetch.sh asia first")
    g = bif.parse_file(path)
    assert (g.number_of_nodes(), g.number_of_edges()) == (8, 8)
    assert {("tub", "either"), ("lung", "either"), ("either", "xray"), ("smoke", "bronc")} <= set(g.edges)


# ── synthetic ───────────────────────────────────────────────────────────────

@pytest.mark.parametrize("model", synthetic.MODELS)
def test_synthetic_is_reproducible_byte_for_byte(model, tmp_path):
    a, b = tmp_path / "a.csv", tmp_path / "b.csv"
    write_edges(a, synthetic.generate(316, 4, model, 3))
    write_edges(b, synthetic.generate(316, 4, model, 3))
    assert a.read_bytes() == b.read_bytes()
    write_edges(b, synthetic.generate(316, 4, model, 4))
    assert a.read_bytes() != b.read_bytes()


@pytest.mark.parametrize("model", synthetic.MODELS)
@pytest.mark.parametrize("n", [100, 316, 1000])
@pytest.mark.parametrize("d", synthetic.DEGREES)
def test_synthetic_shape(model, n, d):
    g = synthetic.generate(n, d, model, 0)
    assert nx.is_directed_acyclic_graph(g)
    assert g.number_of_nodes() == n
    assert abs(g.number_of_edges() - d * n / 2) <= 0.05 * d * n / 2
    assert all(int(u[1:]) < int(v[1:]) for u, v in g.edges)


# ── workloads ───────────────────────────────────────────────────────────────

def test_pairs_are_reproducible_distinct_descendants():
    g = synthetic.generate(316, 4, "er", 0)
    ps = workloads.pairs(g)
    assert ps == workloads.pairs(g)
    assert len(ps) == 200 == len(set(ps))
    assert all(y in nx.descendants(g, t) for t, y in ps)


def test_pairs_take_everything_on_small_graphs():
    g = nx.DiGraph([("a", "b"), ("b", "c")])
    assert sorted(workloads.pairs(g)) == [("a", "b"), ("a", "c"), ("b", "c")]


def test_triples_respect_sizes_and_disjointness():
    g = synthetic.generate(100, 2, "ba", 1)
    ts = workloads.triples(g)
    assert ts == workloads.triples(g)
    assert [len(z) for _, _, z in ts] == [s for s in workloads.Z_SIZES for _ in range(50)]
    assert all(x != y and x not in z and y not in z and len(set(z)) == len(z) for x, y, z in ts)
    assert workloads.z_sizes(8) == [0, 1, 3, 6]


# ── reference ───────────────────────────────────────────────────────────────

CHAIN = [("a", "b"), ("b", "c")]
FORK = [("b", "a"), ("b", "c")]
COLLIDER = [("a", "b"), ("c", "b")]


@pytest.mark.parametrize("edges,z,expected", [
    (CHAIN, [], False), (CHAIN, ["b"], True),
    (FORK, [], False), (FORK, ["b"], True),
    (COLLIDER, [], True), (COLLIDER, ["b"], False),
])
def test_pgmpy_dseparation_on_toy_dags(edges, z, expected):
    assert reference.separated(reference._dag(edges), "a", "c", z) is expected


@pytest.mark.parametrize("edges,latent,expected", [
    ([("z", "t"), ("z", "y"), ("t", "y")], (), {"z"}),                      # confounder
    ([("t", "m"), ("m", "y"), ("c", "t"), ("c", "y")], (), {"c"}),          # mediator excluded
    ([("u", "t"), ("u", "y"), ("t", "y")], ("u",), None),                   # latent confounder
])
def test_canonical_adjustment_on_toy_dags(edges, latent, expected):
    g = nx.DiGraph(edges)
    assert reference.canonical(g, "t", "y", frozenset(latent)) == expected


def test_dagitty_matches_on_toy_dags():
    if reference.dagitty_version() is None:
        pytest.skip("R + dagitty not available")
    g = nx.DiGraph([("z", "t"), ("z", "y"), ("t", "y"), ("t", "m"), ("m", "y"), ("w", "z")])
    dsep, adj, _ = reference.compute(g, [("t", "y")], [("w", "y", ["z", "t"])])
    assert dsep[0] == {"x": "w", "y": "y", "z": ["z", "t"], "pgmpy": True, "dagitty": True}
    assert adj[0]["canonical"] == ["z", "w"]
    assert adj[0]["minimal"] == [["z"]]  # {w} leaves t <- z -> y open
    assert adj[0]["identifiable"]


def test_read_dataset_roundtrip(tmp_path):
    g = synthetic.generate(100, 2, "er", 0)
    write_edges(tmp_path / "edges.csv", g)
    (tmp_path / "meta.json").write_text(f'{{"n": 100, "m": {g.number_of_edges()}}}')
    assert set(read_dataset(tmp_path).edges) == set(g.edges)


@pytest.mark.parametrize("suffix", [".json", ".json.gz"])
def test_rows_json_roundtrips_and_is_byte_stable(tmp_path, suffix):
    import gzip, json
    from causal4j_bench.io import write_rows_json
    rows = [{"t": "a", "y": "b", "minimal": [["c"]]}, {"t": "a", "y": "c", "minimal": None}]
    a, b = tmp_path / f"a{suffix}", tmp_path / f"b{suffix}"
    write_rows_json(a, {"dataset": "toy"}, "pairs", rows)
    write_rows_json(b, {"dataset": "toy"}, "pairs", rows)
    assert a.read_bytes() == b.read_bytes()
    raw = gzip.decompress(a.read_bytes()) if suffix.endswith(".gz") else a.read_bytes()
    assert json.loads(raw) == {"dataset": "toy", "pairs": rows}
