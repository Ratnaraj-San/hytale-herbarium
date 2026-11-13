"""Where plants generate: regions, named zones, portals (translation keys),
plus prefab->zone inheritance for prefab-placed plants (e.g. tree leaves)."""
import re
from collections import defaultdict

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
ZONE_JSON_RE = re.compile(r"^Server/World/Default/Zones/([^/]+)/Zone\.json$")
WORLD_EVENT_RE = re.compile(r"^Server/WorldEvent/.+\.json$")

# HytaleGenerator biome dir -> world region prefix. The biome->zone link lives in the
# game engine (BiomeGenerator seeds like "Grasslands-Biomes-Distrobution"), not in the
# assets, so this map stays manual — but validate_biome_map() below warns when it goes
# stale (unknown biome dir, or prefix with no valid region). Portal_* dirs map to
# portals at runtime.
BIOME_DIR_TO_REGION_PREFIX = {
    "Plains1": "Zone1",
    "Desert1": "Zone2",
    "Taiga1": "Zone3",
    "Boreal1": "Zone3",
    "Volcanic1": "Zone4",
    "Ocean1": "Oceans",
}

# Only real world regions are kept: Oceans and ZoneN_Tier* (which map to the 5 named
# zones). Drops Masks/, Layers/, Noise/, PrefabPatterns_Shared/, Spawn/Temple/Shore...
# Shallow_Ocean areas belong to the Oceans zone (aquatic plants).
REGION_RE = re.compile(r"^(Oceans|Zone[1-4]_Tier\d+)$")


def normalize_region(region):
    if "_Shallow_Ocean" in region:
        return "Oceans"
    return region


def is_valid_region(region):
    return bool(REGION_RE.match(normalize_region(region)))


def scan_region_zone_names(assets):
    """region dir -> Discovery.ZoneName, read from Zones/<region>/Zone.json (game data).
    Replaces the old hard-coded REGION_TO_NAMED_ZONE map; new zones from game updates
    are picked up automatically."""
    region_to_zone_name = {}
    for entry_name in assets.entry_names:
        match = ZONE_JSON_RE.match(entry_name)
        if not match:
            continue
        data = assets.load_json(entry_name) or {}
        zone_name = (data.get("Discovery") or {}).get("ZoneName")
        if zone_name:
            region_to_zone_name[match.group(1)] = zone_name
    return region_to_zone_name


def validate_biome_map(assets, region_zone_names):
    """Warns when BIOME_DIR_TO_REGION_PREFIX no longer matches the game data: unknown
    biome dir (no Assignments/<dir>/), or region prefix with no zone in the game."""
    assignment_dirs = {
        entry_name.split("/")[3]
        for entry_name in assets.entry_names
        if entry_name.startswith("Server/HytaleGenerator/Assignments/") and entry_name.count("/") > 3
    }
    for biome_dir, prefix in sorted(BIOME_DIR_TO_REGION_PREFIX.items()):
        if biome_dir not in assignment_dirs:
            print(f"WARNING: biome dir {biome_dir!r} not found in HytaleGenerator/Assignments/")
        if not any(region.startswith(prefix) for region in region_zone_names):
            print(f"WARNING: no region with prefix {prefix!r} for biome {biome_dir!r}")


def load_lang_keys(assets):
    """Game lang files are unprefixed; the runtime prefix is "server."."""
    keys = set()
    for entry_name in assets.entry_names:
        if entry_name.startswith("Server/Languages/en-US/") and entry_name.endswith(".lang"):
            for line in assets.read_text(entry_name).splitlines():
                key = line.split("=", 1)[0].strip()
                if key:
                    keys.add(key)
    return keys


def load_lang_values(assets, lang="en-US"):
    """lang key (unprefixed) -> display value, e.g. map.region.Zone3_Shore_Tier1 -> "Shore"."""
    values = {}
    prefix = f"Server/Languages/{lang}/"
    for entry_name in assets.entry_names:
        if entry_name.startswith(prefix) and entry_name.endswith(".lang"):
            for line in assets.read_text(entry_name).splitlines():
                if "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
    return values


def make_key_fns(lang_keys, region_zone_names):
    def server_key(lang_key):
        return "server." + lang_key if lang_key in lang_keys else lang_key

    def region_key(region):
        return server_key(f"map.region.{region}")

    def zone_key_for_region(region):
        zone = region_zone_names.get(region) or region_zone_names.get(normalize_region(region))
        return server_key(f"map.zone.{zone}") if zone else region

    return server_key, region_key, zone_key_for_region


def scan_regions_by_zone(assets, region_zone_names=None):
    """Named zone (server.map.zone.*) -> sorted region keys (server.map.region.*),
    read from Discovery.ZoneName in each region's Zone.json. Regenerated on every
    scanPlants run, so new zones/regions from game updates are picked up.
    Regions whose translation is identical within a zone (e.g. Zone3_Shore_Tier1/2/3
    all display "Shore") are deduplicated by display value, keeping the first key."""
    if region_zone_names is None:
        region_zone_names = scan_region_zone_names(assets)
    server_key, region_key, _ = make_key_fns(load_lang_keys(assets), region_zone_names)
    lang_values = load_lang_values(assets)
    zone_to_regions = defaultdict(list)
    for region, zone_name in region_zone_names.items():
        zone_to_regions[server_key(f"map.zone.{zone_name}")].append(region_key(region))
    result = {}
    for zone, regions in sorted(zone_to_regions.items()):
        seen_values = set()
        deduped = []
        for region_translation_key in sorted(regions):
            value = lang_values.get(region_translation_key[len("server."):], region_translation_key)
            if value in seen_values:
                continue
            seen_values.add(value)
            deduped.append(region_translation_key)
        result[zone] = deduped

    # Player progression order: Zone1, Zone2, Zone3, Zone4, then the rest (Oceans).
    def zone_number(zone_entry):
        for region_translation_key in zone_entry[1]:
            match = re.match(r"server\.map\.region\.Zone(\d+)_", region_translation_key)
            if match:
                return int(match.group(1))
        return 99

    return dict(sorted(result.items(), key=zone_number))


def load_portals(assets):
    """portal type -> (instance id, translation key from Description.DisplayName)."""
    portals = {}
    for entry_name in assets.entry_names:
        match = PORTAL_TYPE_RE.match(entry_name)
        if match:
            data = assets.load_json(entry_name) or {}
            display_name_key = (
                (data.get("Description") or {}).get("DisplayName")
                or f"server.portals.{match.group(1)}"
            )
            portals[match.group(1)] = (data.get("InstanceId", match.group(1)), display_name_key)
    return portals


def _prefab_prefixes(custom_data):
    """Server/Prefabs/... prefixes referenced by a Custom file's Prefabs.Entries."""
    entries = ((custom_data.get("Prefabs") or {}).get("Entries")) or []
    for entry in entries:
        for prefab_ref in entry.get("Prefab", []):
            if not isinstance(prefab_ref, str):
                continue
            rel = prefab_ref.replace(".", "/")
            yield "Server/Prefabs/" + (rel[:-2] + "/" if rel.endswith("/*") else rel)


def _pattern_files(custom_data):
    """Pattern file paths referenced by a Custom file's Prefabs entries
    (e.g. "Zones.Zone1_Tier1.PrefabPatterns.Mountain_Boulders")."""
    entries = ((custom_data.get("Prefabs") or {}).get("Entries")) or []
    for entry in entries:
        pattern_file_ref = (entry.get("Pattern") or {}).get("File")
        if isinstance(pattern_file_ref, str):
            yield "Server/World/Default/" + pattern_file_ref.replace(".", "/") + ".json"


def scan_locations(assets):
    """Returns (plant_locations, portals, prefab_plants, prefab_backed):
    plant_locations[plant_id] = {"regions": set, "zones": set, "portals": set} (raw keys;
    converted to translation keys at output).
    prefab_plants[full prefab path] = set of plant ids inside that prefab."""
    plant_locations = defaultdict(lambda: {"regions": set(), "zones": set(), "portals": set()})
    prefab_locations = defaultdict(lambda: {"regions": set(), "zones": set(), "portals": set()})
    prefab_dir_regions = defaultdict(set)
    prefab_plants = defaultdict(set)
    portals = load_portals(assets)

    all_regions = {
        normalize_region(entry_name.split("/")[4])
        for entry_name in assets.entry_names
        if entry_name.startswith("Server/World/Default/Zones/") and entry_name.count("/") > 4
    }
    all_regions = {region for region in all_regions if is_valid_region(region)}
    zone_custom_patterns = defaultdict(set)  # region -> pattern file paths

    # pass 0: prefab contents (needed to resolve prefab references in zone/assignment files)
    for entry_name in assets.entry_names:
        if PREFAB_DEF_RE.match(entry_name):
            found_ids = set(PLANT_RE.findall(assets.read_text(entry_name)))
            if found_ids:
                prefab_plants[entry_name].update(found_ids)

    # PrefabList name -> prefab dir prefixes (e.g. "Trees" -> Trees/*)
    prefab_lists = {}
    for entry_name in assets.entry_names:
        match = re.match(r"^Server/PrefabList/([^/]+)\.json$", entry_name)
        if match:
            list_data = assets.load_json(entry_name)
            prefixes = set()
            for prefab_entry in (list_data or {}).get("Prefabs", []):
                prefab_path = prefab_entry.get("Path")
                if prefab_path:
                    prefixes.add("Server/Prefabs/" + prefab_path.rstrip("/") + "/")
            prefab_lists[match.group(1)] = prefixes

    def plants_via_prefab_refs(text):
        """Resolve quoted prefab references (slashed path, dotted id, wildcard, or
        PrefabList name) to plant ids."""
        found_ids = set()
        for match in re.finditer(r'"([^"]+)"', text):
            reference = match.group(1)
            if reference in prefab_lists:
                for prefix in prefab_lists[reference]:
                    for prefab_file, plant_ids in prefab_plants.items():
                        if prefab_file.startswith(prefix):
                            found_ids |= plant_ids
                continue
            if ".prefab.json" in reference or reference.count(".") >= 2 or (
                "/" in reference
                and not reference.startswith(("Common/", "Icons/", "Blocks/", "Items/"))
            ):
                resolved_prefab_path = (
                    reference
                    if reference.startswith("Server/")
                    else "Server/Prefabs/"
                    + (reference.replace(".", "/") if "." in reference and "/" not in reference else reference)
                )
                if resolved_prefab_path.endswith(".*"):
                    resolved_prefab_path = resolved_prefab_path[:-2]
                if resolved_prefab_path.endswith("/*"):
                    resolved_prefab_path = resolved_prefab_path[:-2]
                if resolved_prefab_path.endswith(".prefab.json"):
                    found_ids |= prefab_plants.get(resolved_prefab_path, set())
                else:
                    found_ids |= prefab_plants.get(resolved_prefab_path + ".prefab.json", set())
                    prefix = resolved_prefab_path.rstrip("/") + "/"
                    for prefab_file, plant_ids in prefab_plants.items():
                        if prefab_file.startswith(prefix):
                            found_ids |= plant_ids
        return found_ids

    # pass 1: every json inside a zone dir (Custom covers, cave covers, node/tile files)
    #         -> plants attributed to that zone/region
    for entry_name in assets.entry_names:
        match = ZONE_ANY_RE.match(entry_name)
        if not match:
            continue
        region = normalize_region(match.group(1))
        if not is_valid_region(region):
            continue
        data = assets.load_json(entry_name)
        direct_ids = find_plant_ids(data) if data is not None else set()
        prefab_ids = plants_via_prefab_refs(assets.read_text(entry_name))
        for plant_id in direct_ids | prefab_ids:
            plant_locations[plant_id]["regions"].add(region)
        for plant_id in prefab_ids:
            prefab_locations[plant_id]["regions"].add(region)
        # Custom files also tell us which prefab dirs and scatter patterns this zone uses
        if "/Custom." in entry_name and isinstance(data, dict):
            for prefix in _prefab_prefixes(data):
                prefab_dir_regions[prefix].add(region)
            for pattern_file in _pattern_files(data):
                zone_custom_patterns[region].add(pattern_file)

    # pass 1b: scatter patterns (local or shared) -> their Mask file lists the blocks
    for entry_name in assets.entry_names:
        match = PREFAB_PATTERN_RE.match(entry_name)
        if match and is_valid_region(match.group(1)):
            zone_custom_patterns[match.group(1)].add(entry_name)
    for region, pattern_files in zone_custom_patterns.items():
        for pattern_file in pattern_files:
            pattern_data = assets.load_json(pattern_file)
            if not isinstance(pattern_data, dict):
                continue
            mask_file_ref = (pattern_data.get("Mask") or {}).get("File")
            if not mask_file_ref:
                continue
            mask_path = "Server/World/Default/" + mask_file_ref.replace(".", "/") + ".json"
            mask_json = assets.load_json(mask_path)
            if mask_json is None:
                continue
            for plant_id in find_plant_ids(mask_json):
                plant_locations[plant_id]["regions"].add(region)

    # pass 1c: HytaleGenerator Assignments/<biome_dir>/ and Biomes/<biome_dir>/ -> region/portal
    for entry_name in assets.entry_names:
        match = ASSIGNMENT_RE.match(entry_name) or re.match(
            r"^Server/HytaleGenerator/Biomes/([^/]+)/.+\.json$", entry_name
        )
        if not match:
            continue
        biome_dir = match.group(1)
        data = assets.load_json(entry_name)
        direct_ids = find_plant_ids(data) if data is not None else set()
        prefab_ids = plants_via_prefab_refs(assets.read_text(entry_name))
        plant_ids = direct_ids | prefab_ids
        if not plant_ids:
            continue
        if biome_dir.startswith("Portal_"):
            suffix = biome_dir[len("Portal_"):].lower()
            portal_key = next(
                (
                    display_name_key
                    for instance_id, display_name_key in portals.values()
                    if instance_id and suffix in instance_id.lower().replace("portals_", "")
                ),
                None,
            )
            if portal_key:
                for plant_id in plant_ids:
                    plant_locations[plant_id]["portals"].add(portal_key)
                for plant_id in prefab_ids:
                    prefab_locations[plant_id]["portals"].add(portal_key)
        else:
            prefix = BIOME_DIR_TO_REGION_PREFIX.get(biome_dir)
            if prefix:
                regions = {region for region in all_regions if region.startswith(prefix)}
                for plant_id in plant_ids:
                    plant_locations[plant_id]["regions"].update(regions)
                for plant_id in prefab_ids:
                    prefab_locations[plant_id]["regions"].update(regions)

    # pass 1d: WorldEvent stages reference BlockTypeLists (e.g. PlantScatter) whose
    # content is the scattered plants; attribute to the matching portal when the
    # event path contains the portal name (e.g. .../Goblin/Breach/... -> Goblin_Breach)
    block_type_lists = {}
    for entry_name in assets.entry_names:
        match = re.match(r"^Server/BlockTypeList/([^/]+)\.json$", entry_name)
        if match:
            # BlockTypeList files are {"Blocks": ["Plant_...", ...]} plain string lists
            block_type_lists[match.group(1)] = set(PLANT_RE.findall(assets.read_text(entry_name)))
    for entry_name in assets.entry_names:
        if not WORLD_EVENT_RE.match(entry_name):
            continue
        text = assets.read_text(entry_name)
        plant_ids = set()
        for list_name, list_ids in block_type_lists.items():
            if f'"{list_name}"' in text:
                plant_ids |= list_ids
        data = assets.load_json(entry_name)
        if data is not None:
            plant_ids |= find_plant_ids(data)
        if not plant_ids:
            continue
        lower_path = entry_name.lower()
        portal_key = next(
            (
                display_name_key
                for portal_type, (instance_id, display_name_key) in portals.items()
                if all(part in lower_path for part in portal_type.lower().split("_"))
            ),
            None,
        )
        if portal_key:
            for plant_id in plant_ids:
                plant_locations[plant_id]["portals"].add(portal_key)
        # unmapped world events are dropped: portals must stay translation keys

    # pass 2: prefab defs (inherit the regions placing them) + instance/portal files
    for entry_name in assets.entry_names:
        if PREFAB_DEF_RE.match(entry_name):
            text = assets.read_text(entry_name)
            found_ids = set(PLANT_RE.findall(text))
            if not found_ids:
                continue
            prefab_plants[entry_name].update(found_ids)
            for prefix, regions in prefab_dir_regions.items():
                if entry_name.startswith(prefix):
                    for plant_id in found_ids:
                        plant_locations[plant_id]["regions"].update(regions)
                        prefab_locations[plant_id]["regions"].update(regions)
        elif INSTANCE_SCAN_RE.match(entry_name):
            data = assets.load_json(entry_name)
            if data is None:
                continue
            plant_ids = find_plant_ids(data)
            if not plant_ids:
                continue
            lower_path = entry_name.lower()
            portal_key = next(
                (
                    display_name_key
                    for instance_id, display_name_key in portals.values()
                    if instance_id and instance_id.lower() in lower_path
                ),
                entry_name.split("/")[2],
            )
            for plant_id in plant_ids:
                plant_locations[plant_id]["portals"].add(portal_key)
                prefab_locations[plant_id]["portals"].add(portal_key)

    # Diagnostic: plants whose only location evidence is a mask/block list,
    # never placed by any prefab (masks are allowlists; prefab content wins).
    mask_only = sorted(
        plant_id
        for plant_id, location in plant_locations.items()
        if any(location[key] for key in ("regions", "zones", "portals"))
        and not any(
            prefab_locations.get(plant_id, {}).get(key) for key in ("regions", "portals")
        )
    )
    if mask_only:
        print("MASK-ONLY plants (in masks/lists, never placed by a prefab):")
        for plant_id in mask_only:
            print("   ", plant_id)

    # Tree leaves are only ever placed as part of a tree prefab: their locations
    # come exclusively from prefab contents, not from mask allowlists. "Plant_Leaves_"
    # is a vanilla naming convention (no structural marker in item JSON distinguishes
    # leaves from fruits in the same tree prefab), so the prefix stays.
    for plant_id in list(plant_locations):
        if plant_id.startswith("Plant_Leaves_"):
            backed = prefab_locations.get(plant_id) or {
                "regions": set(),
                "zones": set(),
                "portals": set(),
            }
            plant_locations[plant_id] = {
                "regions": set(backed["regions"]),
                "zones": set(),
                "portals": set(backed["portals"]),
            }

    return plant_locations, portals, prefab_plants, prefab_locations
