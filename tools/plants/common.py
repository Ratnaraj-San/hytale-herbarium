"""Shared paths and helpers for the plant scan/compare tools."""
import json
from pathlib import Path

HERE = Path(__file__).parent
SCAN_FILE = HERE / "plants-scan.json"
FINAL_FILE = HERE.parent.parent / "src/main/resources/plants-v2.json"
PLANTS_JSON = HERE.parent.parent / "src/main/resources/plants.json"
REGIONS_BY_ZONE_FILE = HERE.parent.parent / "src/main/resources/regions-by-zone.json"


def flatten(data):
    """category -> [entries]  =>  {id: entry}"""
    out = {}
    for cat, items in data.items():
        if cat.startswith("_"):
            continue
        for entry in items:
            if isinstance(entry, dict) and "id" in entry:
                out[entry["id"]] = entry
    return out


def recap(label, by_id):
    obt = sum(1 for entry in by_id.values() if entry.get("obtainableInExplorationMode"))
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


def apply_obtainable_rules(by_id, force_unobtainable=()):
    """Fixpoint post-processing (see AGENTS.md).

    Base rule: apply_obtainable_rule. Then set obtainableInExplorationMode = false
    when ALL of:
      - every locations list is empty (regions/zones/portals) -> not generated in the world
      - obtainedFrom.crafting is falsy -> not player-craftable
      - obtainedFrom.plants is empty -> does not come from another item
    Rationale: an item that neither generates in the world, nor is crafted, nor comes
    from another item cannot be obtained.

    force_unobtainable: ids that must stay unobtainable whatever the signals say
    (e.g. orphan block-state items whose state is not defined on the block).
    """
    for entry in by_id.values():
        apply_obtainable_rule(entry)
        loc = entry.get("locations") or {}
        locations_empty = all(not loc.get(k) for k in ("regions", "zones", "portals"))
        o = entry.get("obtainedFrom") or {}
        if locations_empty and not o.get("crafting") and not o.get("plants"):
            entry["obtainableInExplorationMode"] = False
    for plant_id in force_unobtainable:
        if plant_id in by_id:
            by_id[plant_id]["obtainableInExplorationMode"] = False
    return by_id


def load_json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))
