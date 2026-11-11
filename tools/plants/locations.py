"""Where plants generate: regions, named zones, portals (translation keys),
plus prefab->zone inheritance for prefab-placed plants (e.g. tree leaves)."""
import re
from collections import defaultdict
from pathlib import Path

from assets_zip import (
    INSTANCE_SCAN_RE,
    PLANT_RE,
    PORTAL_TYPE_RE,
    PREFAB_DEF_RE,
    find_plant_ids,
)

ZONE_ANY_RE = re.compile(r"^Server/World/Default/Zones/([^/]+)/.+\.json$")
PREFAB_PATTERN_RE = re.compile(r"^Server/World/Default/Zones/([^/]+)/PrefabPatterns/[^/]+\.json$")
ASSIGNMENT_RE = re.compile(r"^Server/HytaleGenerator/Assignments/([^/]+)/.+\.json$")

# HytaleGenerator biome dir -> world region prefix (verified against Zone.json seeds:
# Grasslands/Drylands/Firelands biomes). Portal_* dirs map to portals at runtime.
BIOME_DIR_TO_REGION_PREFIX = {
    "Plains1": "Zone1",
    "Desert1": "Zone2",
    "Taiga1": "Zone3",
    "Boreal1": "Zone3",
    "Volcanic1": "Zone4",
    "Ocean1": "Oceans",
}

# Only real world regions are kept: Oceans and ZoneN_Tier* (which map to the 5 named
# zones). Drops Masks/, Layers/, Noise/, PrefabPatterns_Shared/, Spawn/Temple...
# Shallow_Ocean areas belong to the Oceans zone.
REGION_RE = re.compile(r"^(Oceans|Zone[1-4]_Tier\d+)$")


def normalize_region(region):
    if "_Shallow_Ocean" in region:
        return "Oceans"
    return region


def is_valid_region(region):
    return bool(REGION_RE.match(normalize_region(region)))

# Named zone (map.zone.*) per region prefix; all keys verified against the game lang files.
REGION_TO_NAMED_ZONE = {
    "Zone1": "Emerald_Wilds",
    "Zone2": "Howling_Sands",
    "Zone3": "Whisperfrost_Frontiers",
    "Zone4": "Devastated_Lands",
    "Oceans": "Oceans",
}


def load_lang_keys(az):
    """Game lang files are unprefixed; the runtime prefix is "server."."""
    keys = set()
    for n in az.names:
        if n.startswith("Server/Languages/en-US/") and n.endswith(".lang"):
            for line in az.read_text(n).splitlines():
                k = line.split("=", 1)[0].strip()
                if k:
                    keys.add(k)
    return keys


def make_key_fns(lang_keys):
    def server_key(lang_key):
        return "server." + lang_key if lang_key in lang_keys else lang_key

    def region_key(region):
        return server_key(f"map.region.{region}")

    def zone_key_for_region(region):
        named = REGION_TO_NAMED_ZONE.get(region.split("_")[0])
        return server_key(f"map.zone.{named}") if named else region

    return server_key, region_key, zone_key_for_region


def load_portals(az):
    """portal type -> (instance id, translation key from Description.DisplayName)."""
    portals = {}
    for n in az.names:
        m = PORTAL_TYPE_RE.match(n)
        if m:
            data = az.load_json(n) or {}
            disp = (data.get("Description") or {}).get("DisplayName") or f"server.portals.{m.group(1)}"
            portals[m.group(1)] = (data.get("InstanceId", m.group(1)), disp)
    return portals


def _prefab_prefixes(data):
    """Server/Prefabs/... prefixes referenced by a Custom file's Prefabs.Entries."""
    entries = ((data.get("Prefabs") or {}).get("Entries")) or []
    for e in entries:
        for p in e.get("Prefab", []):
            if not isinstance(p, str):
                continue
            rel = p.replace(".", "/")
            yield "Server/Prefabs/" + (rel[:-2] + "/" if rel.endswith("/*") else rel)


def _pattern_files(data):
    """Pattern file paths referenced by a Custom file's Prefabs entries
    (e.g. "Zones.Zone1_Tier1.PrefabPatterns.Mountain_Boulders")."""
    entries = ((data.get("Prefabs") or {}).get("Entries")) or []
    for e in entries:
        f = (e.get("Pattern") or {}).get("File")
        if isinstance(f, str):
            yield "Server/World/Default/" + f.replace(".", "/") + ".json"


def scan_locations(az):
    """Returns (loc, portals, prefab_plants):
    loc[plant] = {"regions": set, "zones": set, "portals": set} (raw keys; converted at output).
    prefab_plants[full prefab path] = set of plant ids inside that prefab."""
    loc = defaultdict(lambda: {"regions": set(), "zones": set(), "portals": set()})
    prefab_dir_regions = defaultdict(set)
    prefab_plants = defaultdict(set)
    portals = load_portals(az)

    all_regions = {normalize_region(r) for r in (n.split("/")[4] for n in az.names
                   if n.startswith("Server/World/Default/Zones/") and n.count("/") > 4)
                   if is_valid_region(r)}
    zone_custom_patterns = defaultdict(set)  # region -> pattern file paths

    # pass 0: prefab contents (needed to resolve prefab references in zone/assignment files)
    for n in az.names:
        if PREFAB_DEF_RE.match(n):
            hits = set(PLANT_RE.findall(az.read_text(n)))
            if hits:
                prefab_plants[n].update(hits)

    # PrefabList name -> prefab dir prefixes (e.g. "Trees" -> Trees/*)
    prefab_lists = {}
    for n in az.names:
        m = re.match(r"^Server/PrefabList/([^/]+)\.json$", n)
        if m:
            d = az.load_json(n)
            prefixes = set()
            for p in (d or {}).get("Prefabs", []):
                ppath = p.get("Path")
                if ppath:
                    prefixes.add("Server/Prefabs/" + ppath.rstrip("/") + "/")
            prefab_lists[m.group(1)] = prefixes

    def plants_via_prefab_refs(text):
        """Resolve quoted prefab references (slashed path, dotted id, wildcard, or
        PrefabList name) to plant ids."""
        out = set()
        for m in re.finditer(r'"([^"]+)"', text):
            s = m.group(1)
            if s in prefab_lists:
                for prefix in prefab_lists[s]:
                    for fp, ids in prefab_plants.items():
                        if fp.startswith(prefix):
                            out |= ids
                continue
            if ".prefab.json" in s or s.count(".") >= 2 or ("/" in s and not s.startswith(("Common/", "Icons/", "Blocks/", "Items/"))):
                full = s if s.startswith("Server/") else "Server/Prefabs/" + (s.replace(".", "/") if "." in s and "/" not in s else s)
                if full.endswith(".*"):
                    full = full[:-2]
                if full.endswith("/*"):
                    full = full[:-2]
                if full.endswith(".prefab.json"):
                    out |= prefab_plants.get(full, set())
                else:
                    out |= prefab_plants.get(full + ".prefab.json", set())  # exact dotted name
                    prefix = full.rstrip("/") + "/"
                    for fp, ids in prefab_plants.items():
                        if fp.startswith(prefix):
                            out |= ids
        return out

    # pass 1: every json inside a zone dir (Custom covers, cave covers, node/tile files)
    #         -> plants attributed to that zone/region
    for n in az.names:
        m = ZONE_ANY_RE.match(n)
        if not m:
            continue
        region = normalize_region(m.group(1))
        if not is_valid_region(region):
            continue
        data = az.load_json(n)
        ids = find_plant_ids(data) if data is not None else set()
        ids |= plants_via_prefab_refs(az.read_text(n))
        for pid in ids:
            loc[pid]["regions"].add(region)
        # Custom files also tell us which prefab dirs and scatter patterns this zone uses
        if "/Custom." in n and isinstance(data, dict):
            for prefix in _prefab_prefixes(data):
                prefab_dir_regions[prefix].add(region)
            for pf in _pattern_files(data):
                zone_custom_patterns[region].add(pf)

    # pass 1b: scatter patterns (local or shared) -> their Mask file lists the blocks
    for n in az.names:
        m = PREFAB_PATTERN_RE.match(n)
        if m and is_valid_region(m.group(1)):
            zone_custom_patterns[m.group(1)].add(n)
    for region, pattern_files in zone_custom_patterns.items():
        for pf in pattern_files:
            pdata = az.load_json(pf)
            if not isinstance(pdata, dict):
                continue
            mask_file = (pdata.get("Mask") or {}).get("File")
            if not mask_file:
                continue
            mask_path = "Server/World/Default/" + mask_file.replace(".", "/") + ".json"
            mask_data = az.load_json(mask_path)
            if mask_data is None:
                continue
            for pid in find_plant_ids(mask_data):
                loc[pid]["regions"].add(region)

    # pass 1c: HytaleGenerator Assignments/<BiomeDir>/ and Biomes/<BiomeDir>/ -> region/portal
    for n in az.names:
        m = ASSIGNMENT_RE.match(n) or re.match(r"^Server/HytaleGenerator/Biomes/([^/]+)/.+\.json$", n)
        if not m:
            continue
        bdir = m.group(1)
        data = az.load_json(n)
        ids = find_plant_ids(data) if data is not None else set()
        ids |= plants_via_prefab_refs(az.read_text(n))
        if not ids:
            continue
        if bdir.startswith("Portal_"):
            suffix = bdir[len("Portal_"):].lower()
            portal_key = next(
                (disp for inst, disp in portals.values()
                 if inst and suffix in inst.lower().replace("portals_", "")),
                None,
            )
            if portal_key:
                for pid in ids:
                    loc[pid]["portals"].add(portal_key)
        else:
            prefix = BIOME_DIR_TO_REGION_PREFIX.get(bdir)
            if prefix:
                for pid in ids:
                    loc[pid]["regions"].update(r for r in all_regions if r.startswith(prefix))

    # pass 1d: WorldEvent stages reference BlockTypeLists (e.g. PlantScatter) whose
    # content is the scattered plants; attribute to the matching portal when the
    # event path contains the portal name (e.g. .../Goblin/Breach/... -> Goblin_Breach)
    blocktype_lists = {}
    for n in az.names:
        m = re.match(r"^Server/BlockTypeList/([^/]+)\.json$", n)
        if m:
            # BlockTypeList files are {"Blocks": ["Plant_...", ...]} plain string lists
            blocktype_lists[m.group(1)] = set(PLANT_RE.findall(az.read_text(n)))
    worldevent_re = re.compile(r"^Server/WorldEvent/.+\.json$")
    for n in az.names:
        if not worldevent_re.match(n):
            continue
        text = az.read_text(n)
        ids = set()
        for name, list_ids in blocktype_lists.items():
            if f'"{name}"' in text:
                ids |= list_ids
        data = az.load_json(n)
        if data is not None:
            ids |= find_plant_ids(data)
        if not ids:
            continue
        low = n.lower()
        portal_key = next(
            (disp for ptype, (inst, disp) in portals.items()
             if all(part in low for part in ptype.lower().split("_"))),
            None,
        )
        if portal_key:
            for pid in ids:
                loc[pid]["portals"].add(portal_key)
        # unmapped world events are dropped: portals must stay translation keys

    # pass 2: prefab defs (inherit the regions placing them) + instance/portal files
    for n in az.names:
        if PREFAB_DEF_RE.match(n):
            text = az.read_text(n)
            hits = set(PLANT_RE.findall(text))
            if not hits:
                continue
            prefab_plants[n].update(hits)
            for prefix, regions in prefab_dir_regions.items():
                if n.startswith(prefix):
                    for pid in hits:
                        loc[pid]["regions"].update(regions)
        elif INSTANCE_SCAN_RE.match(n):
            data = az.load_json(n)
            if data is None:
                continue
            ids = find_plant_ids(data)
            if not ids:
                continue
            low = n.lower()
            portal_key = next(
                (disp for inst, disp in portals.values() if inst and inst.lower() in low),
                n.split("/")[2],
            )
            for pid in ids:
                loc[pid]["portals"].add(portal_key)

    return loc, portals, prefab_plants
