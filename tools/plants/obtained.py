"""How plants are obtained: source-plant relations (salvage/drop), shears,
break/collect interactions, crafting recipe. All read from Assets.zip."""
from collections import defaultdict

from assets_zip import (
    DROP_RE,
    ITEM_RE,
    PLANT_ID_RE,
    SALVAGE_RE,
    collect_item_ids,
)
from common import PLANTS_JSON, load_json_file


# Fallback for plant ids not yet listed in plants.json (v1): dir-name normalization
# (all categories are singular: Bushes -> Bush) plus ID-prefix overrides.
DIR_TO_CATEGORY = {"Bushes": "Bush", "Flowers": "Flower"}
PREFIX_TO_CATEGORY = [
    ("Plant_Sapling", "Sapling"),
    ("Plant_Seeds_", "Seeds"),
    ("Plant_Seed_", "Seeds"),
    ("Plant_Vine", "Vine"),
    ("Plant_Liana", "Vine"),
    ("Plant_Crop_Mushroom", "Mushroom"),
    ("Plant_Mushroom", "Mushroom"),
    ("Plant_Fern", "Fern"),
]


def load_plant_categories():
    """plant item id -> category, read from plants.json (v1) — the source of truth
    for category names. New plants missing from it fall back to categorize()."""
    data = load_json_file(PLANTS_JSON)
    outputs = {}
    for cat, items in data.items():
        if cat.startswith("_"):
            continue
        for it in items:
            if isinstance(it, dict) and "id" in it:
                outputs[it["id"]] = cat
    return outputs


def categorize(plant_id, dir_name):
    for prefix, cat in PREFIX_TO_CATEGORY:
        if plant_id.startswith(prefix):
            return cat
    return DIR_TO_CATEGORY.get(dir_name, dir_name)


def index_items(names, plant_categories=None):
    """item id -> (path, category). Category comes from plants.json when known,
    else from the dir/prefix fallback."""
    if plant_categories is None:
        plant_categories = load_plant_categories()
    item_path = {}
    item_category = {}
    for entry_name in names:
        match = ITEM_RE.match(entry_name)
        if match:
            item_path[match.group(2)] = entry_name
            parts = match.group(1).split("/")
            dir_name = parts[1] if parts[0] == "Plant" and len(parts) > 1 else parts[0]
            item_category[match.group(2)] = plant_categories.get(
                match.group(2), categorize(match.group(2), dir_name))
    return item_path, item_category


def index_drops(names):
    """drops file stem (without .json and without the Drops_ prefix) -> path"""
    outputs = {}
    for entry_name in names:
        match = DROP_RE.match(entry_name)
        if match:
            stem = match.group(1)
            if stem.startswith("Drops_"):
                stem = stem[len("Drops_"):]
            outputs[stem] = entry_name
    return outputs


def scan_salvage_relations(assets):
    """List of (source item, obtained item) from Salvage recipes."""
    salvage_relations = []
    for entry_name in assets.entry_names:
        if not SALVAGE_RE.match(entry_name):
            continue
        data = assets.load_json(entry_name)
        if not data:
            continue
        inputs = {i.get("ItemId") for i in data.get("Input", []) if isinstance(i, dict)}
        outputs = set()
        po = data.get("PrimaryOutput")
        if isinstance(po, dict) and po.get("ItemId"):
            outputs.add(po["ItemId"])
        collect_item_ids(data.get("Output", []), outputs)
        for input_entry in inputs:
            for outputs in outputs:
                salvage_relations.append((input_entry, outputs))
    return salvage_relations


class ItemChain:
    """Walks an item's Parent chain (cached JSON via AssetsZip)."""

    def __init__(self, assets, item_path):
        self.assets = assets
        self.item_path = item_path

    def _data(self, plant_id):
        p = self.item_path.get(plant_id)
        return self.assets.load_json(p) if p else None

    def _chain(self, plant_id):
        seen = set()
        cur = plant_id
        while cur and cur in self.item_path and cur not in seen:
            seen.add(cur)
            data = self._data(cur)
            if not data:
                break
            yield cur, data
            cur = data.get("Parent")

    def tools_of(self, plant_id):
        """Merge Tools along the whole Parent chain (a child may override Gathering)."""
        tools = []
        for _, data in self._chain(plant_id):
            gathering = (data.get("BlockType") or {}).get("Gathering")
            if gathering:
                tools += [t.get("Type") for t in gathering.get("Tools", []) if isinstance(t, dict) and t.get("Type")]
        return tools

    def stage_fruit_drops(self, plant_id, drop_path):
        """Plant_Fruit_* items dropped by block STATES (e.gathering. the berry-laden StageFinal
        of a bush): returns {item_id: {"drop": via Soft/break, "collect": via Harvest}}.
        Only fruit outputs are kept — regular crop produce is already covered by the
        crop-stage relations."""
        outputs = {}
        for _, data in self._chain(plant_id):
            block_type = data.get("BlockType") or {}
            states = ((block_type.get("State") or {}).get("Definitions")) or {}
            for state_definition in states.values():
                gathering = (state_definition or {}).get("Gathering") or {}
                for key, flag in (("Soft", "drop"), ("Harvest", "collect")):
                    entry = gathering.get(key)
                    drop_list = entry.get("DropList") if isinstance(entry, dict) else None
                    if not isinstance(drop_list, str):
                        continue
                    stem = drop_list[len("Drops_"):] if drop_list.startswith("Drops_") else drop_list
                    path = drop_path.get(stem)
                    if not path:
                        continue
                    items = set()
                    collect_item_ids(self.assets.load_json(path) or {}, items)
                    for it in items:
                        if it.startswith("Plant_Fruit_"):
                            cur = outputs.setdefault(it, {"drop": False, "collect": False})
                            cur[flag] = True
        return outputs

    def uses_crystal_fertilizer(self, plant_id, crop_ids=None):
        """True when the plant needs a crystal soil (any Support BlockTypeId containing
        "Crystal"), or when the matching seeds item has a crystal seed condition (any
        interaction value containing "Crystal"). Both are detected by name pattern from
        the game data instead of hard-coded ids."""
        for _, data in self._chain(plant_id):
            block_type = data.get("BlockType") or {}
            support = block_type.get("Support") or {}
            for face, blocks in support.items():
                for b in blocks or []:
                    if isinstance(b, dict) and "Crystal" in str(b.get("BlockTypeId") or ""):
                        return True
        if crop_ids is not None and plant_id in crop_ids or plant_id.startswith("Plant_Crop_"):
            base = plant_id[len("Plant_Crop_"):]
            for suffix in ("_Item", "_Block"):
                if base.endswith(suffix):
                    base = base[: -len(suffix)]
                    break
            seeds = f"Plant_Seeds_{base}"
            for _, data in self._chain(seeds):
                interactions = data.get("Interactions") or {}
                if any("Crystal" in str(value) for value in interactions.values()):
                    return True
        return False

    def drop_lists_of(self, plant_id):
        outputs = []

        def rec(o):
            if isinstance(o, dict):
                for key, value in o.items():
                    if key == "DropList" and isinstance(value, str):
                        outputs.append(value)
                    else:
                        rec(value)
            elif isinstance(o, list):
                for x in o:
                    rec(x)

        for _, data in self._chain(plant_id):
            gathering = (data.get("BlockType") or {}).get("Gathering")
            if gathering:
                rec(gathering)
        return outputs

    def own_recipe(self, plant_id):
        """Crafting is NOT inherited: a parent'source Recipe crafts the parent item."""
        data = self._data(plant_id)
        return (data or {}).get("Recipe")

    def has_collect_interaction(self, plant_id, drop_path):
        """Collect = block-state Interactions (e.gathering. HarvestCrop), a Gathering.Harvest
        key, or a *_Harvest drop list, anywhere in the Parent chain."""
        if any(source.endswith("_Harvest") for source in drop_path if source == plant_id or source.startswith(plant_id + "_")):
            return True
        for _, data in self._chain(plant_id):
            block_type = data.get("BlockType") or {}
            gathering = block_type.get("Gathering")
            if isinstance(gathering, dict) and "Harvest" in gathering:
                return True
            for stage in (block_type.get("State") or {}).get("Definitions", {}).values():
                if isinstance(stage, dict) and stage.get("Interactions"):
                    return True
        return False

    def has_break_drop(self, plant_id, drop_path):
        """Break = breaking drops the item itself, either via:
        - a non-harvest Drops file of this plant containing ItemId == plant_id
          (e.gathering. Plant_Barnacles drops only fibre -> false), or
        - a Gathering.Breaking key in the Parent chain (no DropList -> the block
          drops itself when broken, e.gathering. cacti with hatchet), or
        - a Gathering.Soft key with no DropList (soft block drops itself, e.gathering. corals;
          Soft.DropList "Empty" means breaking gives nothing, e.gathering. grasses)."""
        for _, data in self._chain(plant_id):
            gathering = (data.get("BlockType") or {}).get("Gathering")
            if not isinstance(gathering, dict):
                continue
            if "Breaking" in gathering:
                return True
            soft = gathering.get("Soft")
            if isinstance(soft, dict) and not soft.get("DropList"):
                return True
        for stem, path in drop_path.items():
            if (stem == plant_id or stem.startswith(plant_id + "_")) and not stem.endswith("_Harvest"):
                data = self.assets.load_json(path)
                if not data:
                    continue
                ids = set()
                collect_item_ids(data, ids)
                if plant_id in ids:
                    return True
        return False

    def has_shears(self, plant_id):
        """Shears = the Gathering.Tools chain includes Shears (shearing yields the item)."""
        return "Shears" in self.tools_of(plant_id)


def drop_list_refs(item_path, item_chain):
    """Drop-list stem -> [(plant item id, gathering key)] for every plant item whose
    Parent chain sets it as a Gathering DropList (Soft = break, Harvest = collect).
    Stems that are not item ids (e.gathering. "Plant_Flower_Blue_Harvest", "Plant_Fiber_Tall")
    are drop lists; this map resolves them back to the real plant items."""
    refs = defaultdict(set)
    for plant_id in item_path:
        if not PLANT_ID_RE.match(plant_id):
            continue
        # per Gathering key (Soft/Harvest/Breaking), the NEAREST chain entry that defines
        # the key wins — a child overriding Soft must not also inherit the parent'source Soft
        resolved = set()
        for _cur, data in item_chain._chain(plant_id):
            gathering = (data.get("BlockType") or {}).get("Gathering") or {}
            for key in ("Soft", "Harvest", "Breaking"):
                if key in resolved or key not in gathering:
                    continue
                resolved.add(key)
                entry = gathering[key]
                drop_list = entry.get("DropList") if isinstance(entry, dict) else None
                if isinstance(drop_list, str):
                    refs[drop_list].add((plant_id, key))
    return {key: sorted(value) for key, value in refs.items()}


def scan_crop_and_stage_ids(assets, item_path):
    """Crop item ids (items whose BlockType has State.Definitions stages) and the set
    of all stage names (Stage1, StageFinal, ...) — read from the game data instead of
    hard-coding the "Plant_Crop_" prefix and the "_StageFinal" suffix."""
    crops, stages = set(), set()
    for plant_id, path in item_path.items():
        if not PLANT_ID_RE.match(plant_id):
            continue
        data = assets.load_json(path) or {}
        states = ((data.get("BlockType") or {}).get("State") or {}).get("Definitions") or {}
        if states:
            # the item/block variants of the same crop share the family
            base = plant_id
            for suffix in ("_Item", "_Block"):
                if plant_id.endswith(suffix):
                    base = plant_id[: -len(suffix)]
                    break
            crops.update({base, base + "_Item", base + "_Block"})
            stages.update(states.keys())
    return crops, stages


def is_crop(plant_id, crop_ids):
    return plant_id in crop_ids if crop_ids is not None else plant_id.startswith("Plant_Crop_")


def has_stage_suffix(source_id, stage_suffixes):
    return (
        any(source_id.endswith("_" + source) for source in stage_suffixes)
        if stage_suffixes is not None
        else "_StageFinal" in source_id
    )


def is_crop_stage(source_id, crop_ids, stage_suffixes):
    """True for block-state ids of a crop (e.gathering. Plant_Crop_Carrot_StageFinal): the id
    ends with a known stage name and its base (without the stage) is a known crop."""
    if not has_stage_suffix(source_id, stage_suffixes):
        return False
    for source in sorted(stage_suffixes or (), key=len, reverse=True):
        if source_id.endswith("_" + source):
            return source_id[: -(len(source) + 1)] in (crop_ids or set())
    return "_StageFinal" in source_id and source_id.startswith("Plant_Crop_") if crop_ids is None else False


def cleanup_plant_sources(plant_id, sources, known_ids, magical=False, harvest_refs=None,
                          crop_ids=None, stage_suffixes=None):
    """Clean up obtainedFrom.plants: replace raw crop block-state sources
    (*_StageFinal / *_Harvest / *_StageN) by the growable source, flagged growth=True:
    - crop target  -> seeds bag (Plant_Seeds_<X>, + _Eternal variant when present)
    - fruit target -> sapling (Plant_Sapling_<X>), or keep the bush source
    - leaves       -> sapling source flagged growth
    - petals       -> "Plant_Flower_<Color>_Harvest" drop lists are resolved to the
      flowers of that color (breaking them drops the petals)
    Other relations are left unchanged."""
    if not sources:
        return sources

    outputs = []

    def add(source_id, growth=False, shears=False, drop=False, salvage=False):
        if source_id not in known_ids:
            return
        for e in outputs:
            if e["id"] == source_id:
                e["shears"] = e["shears"] or shears
                e["drop"] = e["drop"] or drop
                e["salvage"] = e["salvage"] or salvage
                e["growth"] = e["growth"] or growth
                e["magical"] = e["magical"] or (magical if growth else False)
                return
        outputs.append({"id": source_id, "shears": shears, "drop": drop, "salvage": salvage,
                    "growth": growth, "magical": magical if growth else False})

    def keep(source):
        add(source["id"], shears=source.get("shears", False), drop=source.get("drop", False),
            salvage=source.get("salvage", False))

    if is_crop(plant_id, crop_ids):
        base = plant_id[len("Plant_Crop_"):]
        for suffix in ("_Item", "_Block"):
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        prefix = f"Plant_Crop_{base}_"
        crop_sources = [source for source in sources if source["id"].startswith(prefix)]
        seeds = f"Plant_Seeds_{base}"
        seeds_eternal = f"Plant_Seeds_{base}_Eternal"
        if crop_sources and seeds in known_ids:
            add(seeds, growth=True)
            if any("_Eternal" in source["id"] for source in crop_sources) and seeds_eternal in known_ids:
                add(seeds_eternal, growth=True)
            for source in sources:
                if not source["id"].startswith(prefix):
                    keep(source)
            return outputs
        return [dict(source) for source in sources]

    if plant_id.startswith("Plant_Fruit_"):
        name = plant_id[len("Plant_Fruit_"):]
        sapling = f"Plant_Sapling_{name}"
        if sapling in known_ids:
            # crop stages -> the sapling that grows the fruit tree
            add(sapling, growth=True)
            for source in sources:
                source_id = source["id"]
                if not is_crop_stage(source_id, crop_ids, stage_suffixes):
                    add(source_id, growth=source_id.startswith(("Plant_Sapling_", "Plant_Bush_")),
                        shears=source.get("shears", False), drop=source.get("drop", False),
                        salvage=source.get("salvage", False))
        else:
            # no sapling (e.gathering. berries): keep the raw sources unchanged; the bush
            # obtainability is a separate known fix (see AGENTS.md Plant data)
            return [dict(source) for source in sources]
        return outputs

    if plant_id.startswith("Plant_Leaves_"):
        for source in sources:
            source_id = source["id"]
            add(source_id, growth=source_id.startswith("Plant_Sapling_"),
                shears=source.get("shears", False), drop=source.get("drop", False),
                salvage=source.get("salvage", False))
        return outputs

    if plant_id.startswith("Plant_Petals_") and harvest_refs:
        for source in sources:
            source_id = source["id"]
            if source_id.endswith("_Harvest") and source_id not in known_ids:
                # drop list, not an item: resolve to the flowers that drop it (break)
                for ref_pid, _key in harvest_refs.get(source_id, []):
                    add(ref_pid, shears=source.get("shears", False), drop=source.get("drop", False),
                        salvage=source.get("salvage", False))
            else:
                keep(source)
        return outputs

    return [dict(source) for source in sources]


def bench_item_map(assets):
    """Bench type Id (BlockType.Bench.Id of bench items) -> bench item id (file name).

    Recipes reference benches by type id (e.gathering. "Farmingbench"); icons/names live on
    the placeable bench item (e.gathering. "Bench_Farming"). When several items declare the
    same bench type, prefer the one whose name matches (Farmingbench -> Bench_Farming).
    """
    mapping = {}
    for name in sorted(entry_name for entry_name in assets.entry_names
                       if entry_name.startswith("Server/Item/Items/Bench/") and entry_name.endswith(".json")):
        data = assets.load_json(name)
        bench = (data.get("BlockType") or {}).get("Bench")
        bench_id = bench.get("Id") if isinstance(bench, dict) else None
        if not bench_id:
            continue
        item_id = name.rsplit("/", 1)[-1][:-5]
        norm_bench = bench_id.lower().replace("_bench", "").replace("bench", "")
        norm_item = item_id.lower().replace("bench_", "").replace("bench", "")
        if bench_id not in mapping or (norm_bench and norm_bench in norm_item):
            mapping[bench_id] = item_id
    return mapping


# Bench types the player cannot obtain (no placeable bench item in the game).
UNOBTAINABLE_BENCHES = {"Loombench"}


def bench_info(assets):
    """Bench type Id -> (bench item id, max tier level), from Bench items' BlockType.Bench.
    The in-game displayed tier after all upgrades is len(TierLevels) + 1 (e.gathering. Alchemy
    bench: 4 TierLevels entries -> "Tier 5"), so that is the max doable RequiredTierLevel.
    When several items declare the same bench type (e.gathering. Farmingbench = Bench_Farming and
    Bench_Trough), keep the one with the highest max tier (the upgradeable bench)."""
    info = {}
    for name in sorted(entry_name for entry_name in assets.entry_names
                       if entry_name.startswith("Server/Item/Items/Bench/") and entry_name.endswith(".json")):
        data = assets.load_json(name)
        bench = (data.get("BlockType") or {}).get("Bench")
        bench_id = bench.get("Id") if isinstance(bench, dict) else None
        if not bench_id:
            continue
        item_id = name.rsplit("/", 1)[-1][:-5]
        tiers = bench.get("TierLevels")
        max_tier = len(tiers) + 1 if isinstance(tiers, list) else 0
        prev = info.get(bench_id)
        if prev is None or max_tier > prev[1]:
            info[bench_id] = (item_id, max_tier)
    return info


def craftable_bench(recipe, bench_info_map):
    """(doable, bench_item_id) for a recipe. Not doable when the bench type has no
    placeable bench item or is not player-obtainable (UNOBTAINABLE_BENCHES), or when a
    required tier level exceeds the bench'source max tier."""
    reqs = recipe.get("BenchRequirement") if isinstance(recipe, dict) else None
    if not isinstance(reqs, list) or not reqs:
        return True, None
    first = None
    for r in reqs:
        if not isinstance(r, dict):
            continue
        bench_id = r.get("Id")
        if bench_id in UNOBTAINABLE_BENCHES:
            return False, None
        info = bench_info_map.get(bench_id)
        if info is None:
            return False, None
        if first is None:
            first = info
        if (r.get("RequiredTierLevel") or 0) > info[1]:
            return False, None
    return True, (first[0] if first else None)


def resolve_bench_items(recipe, bench_items):
    """Add BenchItemId to each BenchRequirement so the UI can show bench icon/name."""
    if not recipe:
        return recipe
    recipe = dict(recipe)
    reqs = recipe.get("BenchRequirement")
    if isinstance(reqs, list):
        recipe["BenchRequirement"] = [
            {**r, "BenchItemId": bench_items.get(r.get("Id"), r.get("Id"))}
            if isinstance(r, dict) else r
            for r in reqs
        ]
    return recipe


def sapling_prefab_links(assets, item_path):
    """sapling item id -> set of full prefab paths it grows into
    (BlockType.Farming.Stages[*].Prefabs[*].Path)."""
    links = defaultdict(set)
    for plant_id, path in item_path.items():
        if not plant_id.startswith("Plant_Sapling"):
            continue
        data = assets.load_json(path)
        if not data:
            continue
        stages = ((data.get("BlockType") or {}).get("Farming") or {}).get("Stages") or {}
        for stage_list in stages.values():
            for stage in stage_list or []:
                if not isinstance(stage, dict):
                    continue
                for p in stage.get("Prefabs", []):
                    ppath = p.get("Path")
                    if ppath:
                        links[plant_id].add("Server/Prefabs/" + ppath)
    return links


def build_relations(assets, drop_path, item_path, salvage_rel):
    """obtained plant A -> {source plant B -> {shears, drop, salvage}}"""
    relations = defaultdict(lambda: defaultdict(lambda: {"shears": False, "drop": False, "salvage": False}))
    for src, outputs in salvage_rel:
        # source must be a plant; output may be any item (used for the reverse "gives")
        if src and outputs and PLANT_ID_RE.match(src) and outputs in item_path:
            relations[outputs][src]["salvage"] = True
    plant_ids = sorted((p for p in item_path if PLANT_ID_RE.match(p)), key=len, reverse=True)
    for stem, path in drop_path.items():
        src = stem if PLANT_ID_RE.match(stem) else None
        if src is None:
            # drops may be named after block states: "Plant_Crop_Carrot_StageFinal_Harvest"
            # belongs to the block item "Plant_Crop_Carrot_Block"
            for plant_id in plant_ids:
                alt = plant_id[:-len("_Block")] if plant_id.endswith("_Block") else plant_id
                if stem.startswith(alt + "_"):
                    src = plant_id
                    break
        if src is None:
            continue
        data = assets.load_json(path)
        if not data:
            continue
        outs = set()
        collect_item_ids(data, outs)
        for outputs in outs:
            # keep any known item as output: non-plant outputs feed the reverse "gives"
            if outputs in item_path:
                relations[outputs][src]["drop"] = True
    return relations
