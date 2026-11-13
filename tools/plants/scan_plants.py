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
from locations import (
    is_valid_region,
    load_lang_keys,
    make_key_fns,
    normalize_region,
    scan_locations,
    scan_region_zone_names,
    scan_regions_by_zone,
    validate_biome_map,
)
from obtained import (
    ItemChain,
    bench_info,
    bench_item_map,
    build_relations,
    cleanup_plant_sources,
    craftable_bench,
    drop_list_refs,
    index_drops,
    index_items,
    resolve_bench_items,
    sapling_prefab_links,
    scan_crop_and_stage_ids,
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
    region_zone_names = scan_region_zone_names(az)
    validate_biome_map(az, region_zone_names)
    _server_key, region_key, zone_key_for_region = make_key_fns(
        load_lang_keys(az), region_zone_names)
    relations = build_relations(az, drop_path, item_path, salvage_rel)
    bench_items = bench_item_map(az)
    binfo = bench_info(az)
    harvest_refs = drop_list_refs(item_path, chain)
    crop_ids, stage_suffixes = scan_crop_and_stage_ids(az, item_path)

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
    # Reverse of obtainedFrom.plants + craft inputs: gives[A][B] = how plant A gives plant B
    gives_map = defaultdict(lambda: defaultdict(
        lambda: {"shears": False, "drop": False, "salvage": False, "collect": False,
                 "growth": False, "magical": False, "craft": False, "bench": None}))
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
            magical=chain.uses_crystal_fertilizer(pid, crop_ids),
            harvest_refs=harvest_refs,
            crop_ids=crop_ids,
            stage_suffixes=stage_suffixes,
        )
        crafting = resolve_bench_items(chain.own_recipe(pid), bench_items)

        for s in sources:
            g = gives_map[s["id"]][pid]
            for k in ("shears", "drop", "salvage", "collect", "growth", "magical"):
                g[k] = g[k] or s.get(k, False)

        # Bushes that grow fruit (e.g. berries) give it on break AND on harvest
        # (StageFinal state Gathering.Soft / Gathering.Harvest drop lists).
        for fruit, flags in chain.stage_fruit_drops(pid, drop_path).items():
            g = gives_map[pid][fruit]
            g["drop"] = g["drop"] or flags["drop"]
            g["collect"] = g["collect"] or flags["collect"]

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
                "crafting": crafting,
                "interactions": {
                    "break": chain.has_break_drop(pid, drop_path),
                    "collect": chain.has_collect_interaction(pid, drop_path),
                    "shears": chain.has_shears(pid),
                },
            },
        })

    # Gives, part 2: non-plant child items (a plant can also drop/salvage non-plant
    # items) and craft relations of ANY item whose recipe uses a plant as ingredient.
    # Craft relations are listed only when the player can actually perform the recipe
    # (bench exists and required tier is within the bench's max tier).
    plant_set = set(all_plants)
    for out, srcs in relations.items():
        if PLANT_ID_RE.match(out):
            continue
        for src, flags in srcs.items():
            # src may be a drop-list stem (e.g. "Plant_Fiber_Tall"), not an item:
            # resolve it to the plant items whose Gathering DropList references it.
            if src in plant_set:
                targets = [src]
            else:
                targets = [p for p, _key in harvest_refs.get(src, []) if p in plant_set]
            for t in targets:
                g = gives_map[t][out]
                # Soft = break: the drop list contents drop on break (e.g. reeds -> fibre)
                if flags.get("drop"):
                    g["drop"] = True
                if flags.get("salvage"):
                    g["salvage"] = True
    # No self entries in Gives: "shears -> itself" is already shown in the
    # Obtained from / Interactions section of the plant itself.
    for iid in item_path:
        recipe = chain.own_recipe(iid)
        if not recipe:
            continue
        doable, bench_item = craftable_bench(recipe, binfo)
        if not doable:
            continue
        for inp in recipe.get("Input") or []:
            pid = inp.get("ItemId") if isinstance(inp, dict) else None
            if pid and pid in plant_set:
                g = gives_map[pid][iid]
                g["craft"] = True
                if bench_item:
                    g["bench"] = bench_item

    for items in result.values():
        for it in items:
            g = gives_map.get(it["id"], {})
            it["gives"] = [
                {k: v for k, v in {"id": gid, **flags}.items() if v is not None}
                for gid, flags in sorted(g.items())
            ]

    # "Plant" is the fallback category for items with no proper category (e.g.
    # Plant_Hay_Bundle): excluded for now; add a specific rule in obtained.py
    # (EXACT_TO_CATEGORY / PREFIX_TO_CATEGORY) when such an item matters.
    result.pop("Plant", None)

    regions_by_zone = scan_regions_by_zone(az, region_zone_names)
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
