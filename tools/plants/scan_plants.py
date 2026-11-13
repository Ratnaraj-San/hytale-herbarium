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
    scan_barter_shops,
    scan_block_states,
    scan_crop_and_stage_ids,
    scan_salvage_relations,
)
from obtained import find_orphan_state_items

OUT_FILE = SCAN_FILE


def main():
    # optional arg: path to Assets.zip (the gradle scanPlants task passes entry from hytale_home)
    assets_arg = next((a for i, a in enumerate(sys.argv[1:]) if not a.startswith("-")), None)
    assets = AssetsZip(resolve_assets_zip(assets_arg))
    item_path, item_category = index_items(assets.entry_names)
    drop_path = index_drops(assets.entry_names)
    salvage_rel = scan_salvage_relations(assets)
    item_chain = ItemChain(assets, item_path)
    locations, _portals, prefab_plants, _prefab_backed = scan_locations(assets)
    region_zone_names = scan_region_zone_names(assets)
    validate_biome_map(assets, region_zone_names)
    _server_key, region_key, zone_key_for_region = make_key_fns(
        load_lang_keys(assets), region_zone_names)
    relations = build_relations(assets, drop_path, item_path, salvage_rel)
    barter_shops = scan_barter_shops(assets)
    bench_items = bench_item_map(assets)
    bench_info_map = bench_info(assets)
    harvest_refs = drop_list_refs(item_path, item_chain)
    crop_ids, stage_suffixes = scan_crop_and_stage_ids(assets, item_path)
    block_states = scan_block_states(assets, item_path)
    orphan_state_items = find_orphan_state_items(item_path, block_states)
    if orphan_state_items:
        print("ORPHAN STATE items (state not defined on the block, not obtainable):")
        for plant_id in sorted(orphan_state_items):
            print("   ", plant_id)

    # leaves (and any plant inside a tree prefab) are obtainable via the tree grown
    # from a craftable sapling: sapling -> Farming stages -> prefab -> leaves
    for sapling, prefab_paths in sapling_prefab_links(assets, item_path).items():
        for prefab_path in prefab_paths:
            for leaf in prefab_plants.get(prefab_path, ()):
                if leaf.startswith("Plant_Leaves"):
                    relations[leaf][sapling]  # source entry, all flags false (grown, not dropped)

    result = defaultdict(list)
    for plant_id in locations:
        locations[plant_id]["regions"] = {normalize_region(r) for r in locations[plant_id]["regions"] if is_valid_region(r)}
    all_plants = sorted(
        {p for p in locations if PLANT_ID_RE.match(p)} | {p for p in item_path if PLANT_ID_RE.match(p)}
    )
    # Reverse of obtainedFrom.plants + craft inputs: gives[A][B] = how plant A gives plant B
    gives_map = defaultdict(lambda: defaultdict(
        lambda: {"shears": False, "drop": False, "salvage": False, "collect": False,
                 "growth": False, "magical": False, "craft": False, "bench": None}))

    # Pass 1: raw signals and cleaned sources for every plant.
    raw = {}
    for plant_id in all_plants:
        own_drops = [
            p for stem, p in drop_path.items()
            if stem == plant_id or stem.startswith(plant_id + "_")
        ]
        drop = bool(own_drops) or any(dl in drop_path for dl in item_chain.drop_lists_of(plant_id))
        sources = []
        for src, flags in sorted(relations.get(plant_id, {}).items()):
            # Shearing a source yields the SOURCE item itself, so a source relation via
            # shears only holds for the plant itself (already shown in its own Interactions
            # section). In particular, shearing a moss block drops the block, not the moss
            # item — moss items come from breaking the block (drop), not shears.
            sources.append({
                "id": src,
                **flags,
                "shears": src == plant_id and item_chain.has_shears(src, drop_path),
            })
        sources = cleanup_plant_sources(
            plant_id, sources, set(all_plants) | set(item_path),
            magical=item_chain.uses_crystal_fertilizer(plant_id, crop_ids),
            harvest_refs=harvest_refs,
            crop_ids=crop_ids,
            stage_suffixes=stage_suffixes,
        )
        raw[plant_id] = {
            "sources": sources,
            "drop": drop,
            "break": item_chain.has_break_drop(plant_id, drop_path),
            "collect": item_chain.has_collect_interaction(plant_id, drop_path),
            "shears": item_chain.has_shears(plant_id, drop_path),
            "salvage": any(src == plant_id for src, _ in salvage_rel),
            "crafting": item_chain.own_recipe(plant_id) is not None,
            "has_location": any(locations[plant_id][k] for k in ("regions", "zones", "portals")),
            # merchant/barter shops selling this item (Server/BarterShops/*.json)
            "other": barter_shops.get(plant_id, []),
        }

    # Pass 2: fixpoint — a source is listed only if the source itself is obtainable, and a
    # plant is obtainable only if it has an obtainable source, an interaction, or a recipe
    # (plus a location, unless it is crafted). Block-states like *_Stage1 and other
    # unobtainable items thus disappear from "Obtained from / Plants" automatically.
    obtainable = {plant_id: True for plant_id in all_plants}
    sources_by_id = {plant_id: list(raw[plant_id]["sources"]) for plant_id in all_plants}
    while True:
        changed = False
        for plant_id in all_plants:
            info = raw[plant_id]
            kept = [s for s in sources_by_id[plant_id] if obtainable.get(s["id"])]
            if kept != sources_by_id[plant_id]:
                sources_by_id[plant_id] = kept
                changed = True
            value = (
                plant_id in item_path
                and plant_id not in orphan_state_items
                and (bool(kept) or info["break"] or info["collect"] or info["shears"] or info["salvage"] or info["crafting"] or bool(info["other"]))
                and (info["has_location"] or info["crafting"] or bool(kept) or bool(info["other"]))
            )
            if obtainable[plant_id] != value:
                obtainable[plant_id] = value
                changed = True
        if not changed:
            break
    dropped = {
        plant_id: sorted(
            s["id"] for s in raw[plant_id]["sources"] if s not in sources_by_id[plant_id]
        )
        for plant_id in all_plants
    }
    dropped = {pid: ids for pid, ids in dropped.items() if ids}
    if dropped:
        print(f"Dropped {sum(len(v) for v in dropped.values())} unobtainable sources, e.g.:")
        for pid, ids in list(dropped.items())[:10]:
            print(f"  {pid} <- {ids}")

    merchant_sold = sorted(pid for pid in all_plants if raw[pid]["other"])
    if merchant_sold:
        print(f"Merchant/barter-sold plants ({len(merchant_sold)}):")
        for pid in merchant_sold:
            print(f"   {pid} <- {[o['nameKey'] for o in raw[pid]['other']]}")

    for plant_id in all_plants:
        obtainable_flag = obtainable[plant_id]
        sources = sources_by_id[plant_id]
        crafting = resolve_bench_items(item_chain.own_recipe(plant_id), bench_items)

        for s in sources:
            gives_entry = gives_map[s["id"]][plant_id]
            for k in ("shears", "drop", "salvage", "collect", "growth", "magical"):
                gives_entry[k] = gives_entry[k] or s.get(k, False)

        # Bushes that grow fruit (e.g. berries) give entry on break AND on harvest
        # (StageFinal state Gathering.Soft / Gathering.Harvest drop lists).
        for fruit, flags in item_chain.stage_fruit_drops(plant_id, drop_path).items():
            gives_entry = gives_map[plant_id][fruit]
            gives_entry["drop"] = gives_entry["drop"] or flags["drop"]
            gives_entry["collect"] = gives_entry["collect"] or flags["collect"]

        info = raw[plant_id]
        result[item_category.get(plant_id, "Unknown")].append({
            "id": plant_id,
            "obtainableInExplorationMode": obtainable_flag,
            "locations": {
                "regions": sorted({region_key(r) for r in locations[plant_id]["regions"]}),
                "zones": sorted({zone_key_for_region(r) for r in locations[plant_id]["regions"]}),
                "portals": sorted(locations[plant_id]["portals"]),
            },
            "obtainedFrom": {
                "plants": sources,
                "crafting": crafting,
                "interactions": {
                    "break": info["break"],
                    "collect": info["collect"],
                    "shears": info["shears"],
                },
                "other": info["other"],
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
            # resolve entry to the plant items whose Gathering DropList references entry.
            if src in plant_set:
                targets = [src]
            else:
                targets = [p for p, _key in harvest_refs.get(src, []) if p in plant_set]
            for t in targets:
                gives_entry = gives_map[t][out]
                # Soft = break: the drop list contents drop on break (e.g. reeds -> fibre)
                if flags.get("drop"):
                    gives_entry["drop"] = True
                if flags.get("salvage"):
                    gives_entry["salvage"] = True
    # No self entries in Gives: "shears -> itself" is already shown in the
    # Obtained from / Interactions section of the plant itself.
    for item_id in item_path:
        recipe = item_chain.own_recipe(item_id)
        if not recipe:
            continue
        doable, bench_item = craftable_bench(recipe, bench_info_map)
        if not doable:
            continue
        for input_entry in recipe.get("Input") or []:
            plant_id = input_entry.get("ItemId") if isinstance(input_entry, dict) else None
            if plant_id and plant_id in plant_set:
                gives_entry = gives_map[plant_id][item_id]
                gives_entry["craft"] = True
                if bench_item:
                    gives_entry["bench"] = bench_item

    for items in result.values():
        for entry in items:
            gives_entry = gives_map.get(entry["id"], {})
            entry["gives"] = [
                {k: v for k, v in {"id": gid, **flags}.items() if v is not None}
                for gid, flags in sorted(gives_entry.items())
            ]

    # "Plant" is the fallback category for items with no proper category (e.g.
    # Plant_Hay_Bundle): excluded for now; add a specific rule in obtained.py
    # (EXACT_TO_CATEGORY / PREFIX_TO_CATEGORY) when such an item matters.
    result.pop("Plant", None)

    regions_by_zone = scan_regions_by_zone(assets, region_zone_names)
    REGIONS_BY_ZONE_FILE.write_text(json.dumps(regions_by_zone, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"-> {REGIONS_BY_ZONE_FILE} ({len(regions_by_zone)} zones)")

    OUT_FILE.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    entries = [entry for items in result.values() for entry in items]
    recap("plants-scan.json", {entry["id"]: entry for entry in entries})
    print(f"-> {OUT_FILE}")

    # temporary final file: only player-obtainable plants (not versioned)
    by_id = {entry["id"]: entry for items in result.values() for entry in items}
    apply_obtainable_rules(by_id, force_unobtainable=orphan_state_items)
    final = {}
    for cat, items in result.items():
        kept = [entry for entry in items if by_id[entry["id"]]["obtainableInExplorationMode"]]
        if kept:
            final[cat] = kept
    FINAL_FILE.write_text(json.dumps(final, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    recap("plants-final.json (obtainable only)", {entry["id"]: entry for items in final.values() for entry in items})
    print(f"-> {FINAL_FILE}")


if __name__ == "__main__":
    sys.exit(main())
