#!/usr/bin/env python3
"""Scan Hytale's Assets.zip for every generated Plant_* and how it is obtained.

Pipeline (see AGENTS.md -> "HOW TO FIND EVERY PLANT GENERATED + OBTAINABLE"):
  locations : locations.py  (zones/regions/portals as translation keys,
              prefab-placed plants inherit the zones of their prefab)
  obtained  : obtained.py   (source-plant relations, shears/break/collect, crafting)

Output: plants/plants-scan.json + recap on stdout.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

from assets_zip import AssetsZip, PLANT_ID_RE, resolve_assets_zip
from common import FINAL_FILE, REGIONS_BY_ZONE_FILE, SCAN_FILE, apply_obtainable_rules, recap
from locations import is_valid_region, load_lang_keys, make_key_fns, normalize_region, scan_locations, scan_regions_by_zone
from obtained import (
    ItemChain,
    bench_item_map,
    build_relations,
    cleanup_plant_sources,
    harvest_drop_refs,
    index_drops,
    index_items,
    resolve_bench_items,
    sapling_prefab_links,
    scan_salvage_relations,
)

OUT_FILE = SCAN_FILE


def main():
    # optional arg: path to Assets.zip (the gradle scanPlants task passes it from hytale_home)
    assets_arg = next((a for i, a in enumerate(sys.argv[1:]) if not a.startswith("-")), None)
    az = AssetsZip(resolve_assets_zip(assets_arg))
    item_path, item_category = index_items(az.names)
    drop_path = index_drops(az.names)
    salvage_rel = scan_salvage_relations(az)
    chain = ItemChain(az, item_path)
    loc, _portals, prefab_plants, _prefab_backed = scan_locations(az)
    _server_key, region_key, zone_key_for_region = make_key_fns(load_lang_keys(az))
    relations = build_relations(az, drop_path, item_path, salvage_rel)
    bench_items = bench_item_map(az)
    harvest_refs = harvest_drop_refs(item_path, chain)

    # leaves (and any plant inside a tree prefab) are obtainable via the tree grown
    # from a craftable sapling: sapling -> Farming stages -> prefab -> leaves
    for sapling, prefab_paths in sapling_prefab_links(az, item_path).items():
        for pp in prefab_paths:
            for leaf in prefab_plants.get(pp, ()):
                if leaf.startswith("Plant_Leaves"):
                    relations[leaf][sapling]  # source entry, all flags false (grown, not dropped)

    result = defaultdict(list)
    for pid in loc:
        loc[pid]["regions"] = {normalize_region(r) for r in loc[pid]["regions"] if is_valid_region(r)}
    all_plants = sorted(
        {p for p in loc if PLANT_ID_RE.match(p)} | {p for p in item_path if PLANT_ID_RE.match(p)}
    )
    for pid in all_plants:
        own_drops = [p for stem, p in drop_path.items() if stem == pid or stem.startswith(pid + "_")]
        drop = bool(own_drops) or any(dl in drop_path for dl in chain.drop_lists_of(pid))
        has_location = any(loc[pid][k] for k in ("regions", "zones", "portals"))
        obtainable = has_location and pid in item_path and (
            drop or chain.has_shears(pid) or any(src == pid for src, _ in salvage_rel)
        )

        sources = []
        for src, flags in sorted(relations.get(pid, {}).items()):
            sources.append({"id": src, **flags, "shears": chain.has_shears(src)})
        sources = cleanup_plant_sources(
            pid, sources, set(all_plants) | set(item_path),
            magical=chain.uses_crystal_fertilizer(pid),
            harvest_refs=harvest_refs,
        )

        result[item_category.get(pid, "Unknown")].append({
            "id": pid,
            "obtainableInExplorationMode": obtainable,
            "locations": {
                "regions": sorted({region_key(r) for r in loc[pid]["regions"]}),
                "zones": sorted({zone_key_for_region(r) for r in loc[pid]["regions"]}),
                "portals": sorted(loc[pid]["portals"]),
            },
            "obtainedFrom": {
                "plants": sources,
                "crafting": resolve_bench_items(chain.own_recipe(pid), bench_items),
                "interactions": {
                    "break": chain.has_break_drop(pid, drop_path),
                    "collect": chain.has_collect_interaction(pid, drop_path),
                    "shears": chain.has_shears(pid),
                },
            },
        })

    # "Plant" is the fallback category for items with no proper category (e.g.
    # Plant_Hay_Bundle): excluded for now; add a specific rule in obtained.py
    # (EXACT_TO_CATEGORY / PREFIX_TO_CATEGORY) when such an item matters.
    result.pop("Plant", None)

    regions_by_zone = scan_regions_by_zone(az)
    REGIONS_BY_ZONE_FILE.write_text(json.dumps(regions_by_zone, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"-> {REGIONS_BY_ZONE_FILE} ({len(regions_by_zone)} zones)")

    OUT_FILE.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    entries = [it for items in result.values() for it in items]
    recap("plants-scan.json", {it["id"]: it for it in entries})
    print(f"-> {OUT_FILE}")

    # temporary final file: only player-obtainable plants (not versioned)
    by_id = {it["id"]: it for items in result.values() for it in items}
    apply_obtainable_rules(by_id)
    final = {}
    for cat, items in result.items():
        kept = [it for it in items if by_id[it["id"]]["obtainableInExplorationMode"]]
        if kept:
            final[cat] = kept
    FINAL_FILE.write_text(json.dumps(final, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    recap("plants-final.json (obtainable only)", {it["id"]: it for items in final.values() for it in items})
    print(f"-> {FINAL_FILE}")


if __name__ == "__main__":
    sys.exit(main())
