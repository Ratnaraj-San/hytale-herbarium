"""Shared paths and helpers for the plant scan/compare tools."""
import json
from pathlib import Path

HERE = Path(__file__).parent
SCAN_FILE = HERE / "plants-scan.json"
FINAL_FILE = HERE / "plants-final.json"
PLANTS_JSON = HERE.parent.parent / "src/main/resources/plants.json"


def flatten(data):
    """category -> [entries]  =>  {id: entry}"""
    out = {}
    for cat, items in data.items():
        if cat.startswith("_"):
            continue
        for it in items:
            if isinstance(it, dict) and "id" in it:
                out[it["id"]] = it
    return out


def recap(label, by_id):
    obt = sum(1 for it in by_id.values() if it.get("obtainableInExplorationMode"))
    print(f"[{label}]")
    print(f"  total plants: {len(by_id)}")
    print(f"  total plants obtainable: {obt}")
    print(f"  total plants unobtainable: {len(by_id) - obt}")


def apply_obtainable_rule(entry):
    """obtainableInExplorationMode = true if at least one true signal in obtainedFrom:
    a source plant, any of interactions.break/collect/shears, or a crafting recipe.
    Fruits are not generated in the world but are collectable from trees (drop/collect)."""
    o = entry.get("obtainedFrom") or {}
    inter = o.get("interactions") or {}
    entry["obtainableInExplorationMode"] = bool(
        o.get("plants")
        or any(inter.get(k) for k in ("break", "collect", "shears"))
        or o.get("crafting") is not None
    )
    return entry


def apply_obtainable_rules(by_id):
    """Fixpoint post-processing (see AGENTS.md).

    Base rule: apply_obtainable_rule. Then, iteratively until stable, set
    obtainableInExplorationMode = false when ALL of:
      - every locations list is empty (regions/zones/portals)
      - obtainedFrom.crafting is falsy
      - obtainedFrom.plants is empty OR no source plant in it is itself obtainable
    """
    for it in by_id.values():
        apply_obtainable_rule(it)
    changed = True
    while changed:
        changed = False
        for pid, it in by_id.items():
            if not it.get("obtainableInExplorationMode"):
                continue
            loc = it.get("locations") or {}
            locations_empty = all(not loc.get(k) for k in ("regions", "zones", "portals"))
            o = it.get("obtainedFrom") or {}
            no_craft = not o.get("crafting")
            sources = o.get("plants") or []
            sources_ok = any(
                by_id.get(s.get("id"), {}).get("obtainableInExplorationMode") for s in sources
            )
            if locations_empty and no_craft and (not sources or not sources_ok):
                it["obtainableInExplorationMode"] = False
                changed = True
    return by_id


def load_json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))
