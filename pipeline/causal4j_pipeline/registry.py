"""Named registries — the extension mechanism.

Discovery algorithms, model hypotheses, resolution policies, refuters, and
graph readers each live in a registry keyed by name. Config selects by name;
adding a new implementation means registering one callable, no core changes.

External packages can contribute via the ``causal4j_pipeline`` entry-point
group::

    [project.entry-points."causal4j_pipeline"]
    my_algo = "my_pkg.module:register"

where ``register`` is a callable receiving this module.
"""

from __future__ import annotations

from typing import Callable, Dict, Iterable


class Registry:
    """A simple name -> factory registry with decorator registration."""

    def __init__(self, kind: str):
        self.kind = kind
        self._items: Dict[str, Callable] = {}

    def register(self, name: str) -> Callable[[Callable], Callable]:
        def deco(obj: Callable) -> Callable:
            key = name.lower()
            if key in self._items:
                raise ValueError(f"{self.kind} '{name}' is already registered")
            self._items[key] = obj
            return obj

        return deco

    def get(self, name: str) -> Callable:
        key = str(name).lower()
        if key not in self._items:
            raise KeyError(
                f"Unknown {self.kind} '{name}'. Available: {', '.join(sorted(self._items)) or '(none)'}"
            )
        return self._items[key]

    def names(self) -> Iterable[str]:
        return sorted(self._items)

    def __contains__(self, name: str) -> bool:
        return str(name).lower() in self._items


DISCOVERY = Registry("discovery algorithm")
HYPOTHESES = Registry("model hypothesis")
RESOLVERS = Registry("resolution policy")
REFUTERS = Registry("refuter")
READERS = Registry("graph reader")

_PLUGINS_LOADED = False


def load_plugins() -> None:
    """Load third-party registrations from entry points (idempotent)."""
    global _PLUGINS_LOADED
    if _PLUGINS_LOADED:
        return
    _PLUGINS_LOADED = True
    try:
        from importlib.metadata import entry_points

        for ep in entry_points(group="causal4j_pipeline"):
            ep.load()(globals())
    except Exception:  # pragma: no cover - plugin loading must never break core
        pass
