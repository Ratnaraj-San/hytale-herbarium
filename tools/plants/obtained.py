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


# Category names follow src/main/resources/plants.json: all singular (Bush, Flower, ...).
DIR_TO_CATEGORY = {"Bushes": "Bush", "Flowers": "Flower"}

# ID-prefix overrides (checked before the directory name), matching plants.json categories.
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


# Exact IDs that form their own family (brambles), matching plants.json.
EXACT_TO_CATEGORY = {
    "Plant_Bush_Bramble": "Bramble",
    "Plant_Leaves_Bramble": "Bramble",
}


def categorize(pid, dir_name):
    if pid in EXACT_TO_CATEGORY:
        return EXACT_TO_CATEGORY[pid]
    for prefix, cat in PREFIX_TO_CATEGORY:
        if pid.startswith(prefix):
            return cat
    return DIR_TO_CATEGORY.get(dir_name, dir_name)


def index_items(names):
    """item id -> (path, category). Category = dir under Items/Plant, normalized
    to the singular names used by plants.json, with ID-prefix overrides."""
    item_path = {}
    item_category = {}
    for n in names:
        m = ITEM_RE.match(n)
        if m:
            item_path[m.group(2)] = n
            parts = m.group(1).split("/")
            dir_name = parts[1] if parts[0] == "Plant" and len(parts) > 1 else parts[0]
            item_category[m.group(2)] = categorize(m.group(2), dir_name)
    return item_path, item_category


def index_drops(names):
    """drops file stem (without .json and without the Drops_ prefix) -> path"""
    out = {}
    for n in names:
        m = DROP_RE.match(n)
        if m:
            stem = m.group(1)
            if stem.startswith("Drops_"):
                stem = stem[len("Drops_"):]
            out[stem] = n
    return out


def scan_salvage_relations(az):
    """List of (source item, obtained item) from Salvage recipes."""
    rel = []
    for n in az.names:
        if not SALVAGE_RE.match(n):
            continue
        data = az.load_json(n)
        if not data:
            continue
        inputs = {i.get("ItemId") for i in data.get("Input", []) if isinstance(i, dict)}
        outputs = set()
        po = data.get("PrimaryOutput")
        if isinstance(po, dict) and po.get("ItemId"):
            outputs.add(po["ItemId"])
        collect_item_ids(data.get("Output", []), outputs)
        for inp in inputs:
            for out in outputs:
                rel.append((inp, out))
    return rel


class ItemChain:
    """Walks an item's Parent chain (cached JSON via AssetsZip)."""

    def __init__(self, az, item_path):
        self.az = az
        self.item_path = item_path

    def _data(self, pid):
        p = self.item_path.get(pid)
        return self.az.load_json(p) if p else None

    def _chain(self, pid):
        seen = set()
        cur = pid
        while cur and cur in self.item_path and cur not in seen:
            seen.add(cur)
            data = self._data(cur)
            if not data:
                break
            yield cur, data
            cur = data.get("Parent")

    def tools_of(self, pid):
        """Merge Tools along the whole Parent chain (a child may override Gathering)."""
        tools = []
        for _, data in self._chain(pid):
            g = (data.get("BlockType") or {}).get("Gathering")
            if g:
                tools += [t.get("Type") for t in g.get("Tools", []) if isinstance(t, dict) and t.get("Type")]
        return tools

    def stage_fruit_drops(self, pid, drop_path):
        """Plant_Fruit_* items dropped by block STATES (e.g. the berry-laden StageFinal
        of a bush): returns {item_id: {"drop": via Soft/break, "collect": via Harvest}}.
        Only fruit outputs are kept — regular crop produce is already covered by the
        crop-stage relations."""
        out = {}
        for _, data in self._chain(pid):
            bt = data.get("BlockType") or {}
            states = ((bt.get("State") or {}).get("Definitions")) or {}
            for sdef in states.values():
                gathering = (sdef or {}).get("Gathering") or {}
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
                    collect_item_ids(self.az.load_json(path) or {}, items)
                    for it in items:
                        if it.startswith("Plant_Fruit_"):
                            cur = out.setdefault(it, {"drop": False, "collect": False})
                            cur[flag] = True
        return out

    def uses_crystal_fertilizer(self, pid):
        """True when the plant needs Soil_Dirt_Crystal (drops Tool_Fertilizer_Crystal):
        either the crop block Support requires it, or the seeds item has the
        Seed_Condition_Crystal secondary interaction (only needed at stage 0)."""
        for _, data in self._chain(pid):
            bt = data.get("BlockType") or {}
            support = bt.get("Support") or {}
            for face, blocks in support.items():
                for b in blocks or []:
                    if isinstance(b, dict) and b.get("BlockTypeId") == "Soil_Dirt_Crystal":
                        return True
        if pid.startswith("Plant_Crop_"):
            base = pid[len("Plant_Crop_"):]
            for suffix in ("_Item", "_Block"):
                if base.endswith(suffix):
                    base = base[: -len(suffix)]
                    break
            seeds = f"Plant_Seeds_{base}"
            for _, data in self._chain(seeds):
                interactions = data.get("Interactions") or {}
                if any("Seed_Condition_Crystal" in str(v) for v in interactions.values()):
                    return True
        return False

    def drop_lists_of(self, pid):
        out = []

        def rec(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k == "DropList" and isinstance(v, str):
                        out.append(v)
                    else:
                        rec(v)
            elif isinstance(o, list):
                for x in o:
                    rec(x)

        for _, data in self._chain(pid):
            g = (data.get("BlockType") or {}).get("Gathering")
            if g:
                rec(g)
        return out

    def own_recipe(self, pid):
        """Crafting is NOT inherited: a parent's Recipe crafts the parent item."""
        data = self._data(pid)
        return (data or {}).get("Recipe")

    def has_collect_interaction(self, pid, drop_path):
        """Collect = block-state Interactions (e.g. HarvestCrop), a Gathering.Harvest
        key, or a *_Harvest drop list, anywhere in the Parent chain."""
        if any(s.endswith("_Harvest") for s in drop_path if s == pid or s.startswith(pid + "_")):
            return True
        for _, data in self._chain(pid):
            bt = data.get("BlockType") or {}
            g = bt.get("Gathering")
            if isinstance(g, dict) and "Harvest" in g:
                return True
            for stage in (bt.get("State") or {}).get("Definitions", {}).values():
                if isinstance(stage, dict) and stage.get("Interactions"):
                    return True
        return False

    def has_break_drop(self, pid, drop_path):
        """Break = breaking drops the item itself, either via:
        - a non-harvest Drops file of this plant containing ItemId == pid
          (e.g. Plant_Barnacles drops only fibre -> false), or
        - a Gathering.Breaking key in the Parent chain (no DropList -> the block
          drops itself when broken, e.g. cacti with hatchet), or
        - a Gathering.Soft key with no DropList (soft block drops itself, e.g. corals;
          Soft.DropList "Empty" means breaking gives nothing, e.g. grasses)."""
        for _, data in self._chain(pid):
            g = (data.get("BlockType") or {}).get("Gathering")
            if not isinstance(g, dict):
                continue
            if "Breaking" in g:
                return True
            soft = g.get("Soft")
            if isinstance(soft, dict) and not soft.get("DropList"):
                return True
        for stem, path in drop_path.items():
            if (stem == pid or stem.startswith(pid + "_")) and not stem.endswith("_Harvest"):
                data = self.az.load_json(path)
                if not data:
                    continue
                ids = set()
                collect_item_ids(data, ids)
                if pid in ids:
                    return True
        return False

    def has_shears(self, pid):
        """Shears = the Gathering.Tools chain includes Shears (shearing yields the item)."""
        return "Shears" in self.tools_of(pid)


def drop_list_refs(item_path, chain):
    """Drop-list stem -> [(plant item id, gathering key)] for every plant item whose
    Parent chain sets it as a Gathering DropList (Soft = break, Harvest = collect).
    Stems that are not item ids (e.g. "Plant_Flower_Blue_Harvest", "Plant_Fiber_Tall")
    are drop lists; this map resolves them back to the real plant items."""
    refs = defaultdict(set)
    for pid in item_path:
        if not PLANT_ID_RE.match(pid):
            continue
        # per Gathering key (Soft/Harvest/Breaking), the NEAREST chain entry that defines
        # the key wins — a child overriding Soft must not also inherit the parent's Soft
        resolved = set()
        for _cur, data in chain._chain(pid):
            gathering = (data.get("BlockType") or {}).get("Gathering") or {}
            for key in ("Soft", "Harvest", "Breaking"):
                if key in resolved or key not in gathering:
                    continue
                resolved.add(key)
                entry = gathering[key]
                drop_list = entry.get("DropList") if isinstance(entry, dict) else None
                if isinstance(drop_list, str):
                    refs[drop_list].add((pid, key))
    return {k: sorted(v) for k, v in refs.items()}


def cleanup_plant_sources(pid, sources, known_ids, magical=False, harvest_refs=None):
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

    out = []

    def add(sid, growth=False, shears=False, drop=False, salvage=False):
        if sid not in known_ids:
            return
        for e in out:
            if e["id"] == sid:
                e["shears"] = e["shears"] or shears
                e["drop"] = e["drop"] or drop
                e["salvage"] = e["salvage"] or salvage
                e["growth"] = e["growth"] or growth
                e["magical"] = e["magical"] or (magical if growth else False)
                return
        out.append({"id": sid, "shears": shears, "drop": drop, "salvage": salvage,
                    "growth": growth, "magical": magical if growth else False})

    def keep(s):
        add(s["id"], shears=s.get("shears", False), drop=s.get("drop", False),
            salvage=s.get("salvage", False))

    if pid.startswith("Plant_Crop_"):
        base = pid[len("Plant_Crop_"):]
        for suffix in ("_Item", "_Block"):
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        prefix = f"Plant_Crop_{base}_"
        crop_sources = [s for s in sources if s["id"].startswith(prefix)]
        seeds = f"Plant_Seeds_{base}"
        seeds_eternal = f"Plant_Seeds_{base}_Eternal"
        if crop_sources and seeds in known_ids:
            add(seeds, growth=True)
            if any("_Eternal" in s["id"] for s in crop_sources) and seeds_eternal in known_ids:
                add(seeds_eternal, growth=True)
            for s in sources:
                if not s["id"].startswith(prefix):
                    keep(s)
            return out
        return [dict(s) for s in sources]

    if pid.startswith("Plant_Fruit_"):
        name = pid[len("Plant_Fruit_"):]
        sapling = f"Plant_Sapling_{name}"
        if sapling in known_ids:
            # crop stages -> the sapling that grows the fruit tree
            add(sapling, growth=True)
            for s in sources:
                sid = s["id"]
                if not (sid.startswith("Plant_Crop_") and "_StageFinal" in sid):
                    add(sid, growth=sid.startswith(("Plant_Sapling_", "Plant_Bush_")),
                        shears=s.get("shears", False), drop=s.get("drop", False),
                        salvage=s.get("salvage", False))
        else:
            # no sapling (e.g. berries): keep the raw sources unchanged; the bush
            # obtainability is a separate known fix (see AGENTS.md Plant data)
            return [dict(s) for s in sources]
        return out

    if pid.startswith("Plant_Leaves_"):
        for s in sources:
            sid = s["id"]
            add(sid, growth=sid.startswith("Plant_Sapling_"),
                shears=s.get("shears", False), drop=s.get("drop", False),
                salvage=s.get("salvage", False))
        return out

    if pid.startswith("Plant_Petals_") and harvest_refs:
        for s in sources:
            sid = s["id"]
            if sid.endswith("_Harvest") and sid not in known_ids:
                # drop list, not an item: resolve to the flowers that drop it (break)
                for ref_pid, _key in harvest_refs.get(sid, []):
                    add(ref_pid, shears=s.get("shears", False), drop=s.get("drop", False),
                        salvage=s.get("salvage", False))
            else:
                keep(s)
        return out

    return [dict(s) for s in sources]


def bench_item_map(az):
    """Bench type Id (BlockType.Bench.Id of bench items) -> bench item id (file name).

    Recipes reference benches by type id (e.g. "Farmingbench"); icons/names live on
    the placeable bench item (e.g. "Bench_Farming"). When several items declare the
    same bench type, prefer the one whose name matches (Farmingbench -> Bench_Farming).
    """
    mapping = {}
    for name in sorted(n for n in az.names
                       if n.startswith("Server/Item/Items/Bench/") and n.endswith(".json")):
        data = az.load_json(name)
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


def bench_info(az):
    """Bench type Id -> (bench item id, max tier level), from Bench items' BlockType.Bench.
    The in-game displayed tier after all upgrades is len(TierLevels) + 1 (e.g. Alchemy
    bench: 4 TierLevels entries -> "Tier 5"), so that is the max doable RequiredTierLevel.
    When several items declare the same bench type (e.g. Farmingbench = Bench_Farming and
    Bench_Trough), keep the one with the highest max tier (the upgradeable bench)."""
    info = {}
    for name in sorted(n for n in az.names
                       if n.startswith("Server/Item/Items/Bench/") and n.endswith(".json")):
        data = az.load_json(name)
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


def craftable_bench(recipe, binfo):
    """(doable, bench_item_id) for a recipe. Not doable when the bench type has no
    placeable bench item or is not player-obtainable (UNOBTAINABLE_BENCHES), or when a
    required tier level exceeds the bench's max tier."""
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
        info = binfo.get(bench_id)
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


def sapling_prefab_links(az, item_path):
    """sapling item id -> set of full prefab paths it grows into
    (BlockType.Farming.Stages[*].Prefabs[*].Path)."""
    links = defaultdict(set)
    for pid, path in item_path.items():
        if not pid.startswith("Plant_Sapling"):
            continue
        data = az.load_json(path)
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
                        links[pid].add("Server/Prefabs/" + ppath)
    return links


def build_relations(az, drop_path, item_path, salvage_rel):
    """obtained plant A -> {source plant B -> {shears, drop, salvage}}"""
    relations = defaultdict(lambda: defaultdict(lambda: {"shears": False, "drop": False, "salvage": False}))
    for src, out in salvage_rel:
        # source must be a plant; output may be any item (used for the reverse "gives")
        if src and out and PLANT_ID_RE.match(src) and out in item_path:
            relations[out][src]["salvage"] = True
    plant_ids = sorted((p for p in item_path if PLANT_ID_RE.match(p)), key=len, reverse=True)
    for stem, path in drop_path.items():
        src = stem if PLANT_ID_RE.match(stem) else None
        if src is None:
            # drops may be named after block states: "Plant_Crop_Carrot_StageFinal_Harvest"
            # belongs to the block item "Plant_Crop_Carrot_Block"
            for pid in plant_ids:
                alt = pid[:-len("_Block")] if pid.endswith("_Block") else pid
                if stem.startswith(alt + "_"):
                    src = pid
                    break
        if src is None:
            continue
        data = az.load_json(path)
        if not data:
            continue
        outs = set()
        collect_item_ids(data, outs)
        for out in outs:
            # keep any known item as output: non-plant outputs feed the reverse "gives"
            if out in item_path:
                relations[out][src]["drop"] = True
    return relations
