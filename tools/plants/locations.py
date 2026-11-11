"""Where plants generate: regions, named zones, portals (translation keys),
plus prefab->zone inheritance for prefab-placed plants (e.g. tree leaves)."""
from collections import defaultdict
from pathlib import Path

from assets_zip import (
    INSTANCE_SCAN_RE,
    PLANT_RE,
    PORTAL_TYPE_RE,
    PREFAB_DEF_RE,
    ZONE_FILE_RE,
    find_plant_ids,
)

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


def scan_locations(az):
    """Returns (loc, portals):
    loc[plant] = {"regions": set, "zones": set, "portals": set} (raw keys; converted at output)."""
    loc = defaultdict(lambda: {"regions": set(), "zones": set(), "portals": set()})
    prefab_dir_regions = defaultdict(set)
    portals = load_portals(az)

    # pass 1: zone files -> cover plants + prefab dir -> region map
    for n in az.names:
        m = ZONE_FILE_RE.match(n)
        if not m:
            continue
        region, rel = m.group(1), m.group(2)
        data = az.load_json(n)
        if data is None:
            continue
        ids = find_plant_ids(data.get("Covers", []) if isinstance(data, dict) else data)
        biome_keys = []
        if isinstance(data, dict):
            bm = (data.get("CustomBiomeGenerator") or {}).get("BiomeMask")
            if isinstance(bm, list):
                biome_keys = [str(b) for b in bm]
        if not biome_keys:
            biome_keys = [Path(rel).stem]
        for pid in ids:
            loc[pid]["regions"].add(region)
            loc[pid]["zones"].update(biome_keys)
        if isinstance(data, dict) and rel.startswith("Custom."):
            for prefix in _prefab_prefixes(data):
                prefab_dir_regions[prefix].add(region)

    # pass 2: prefab defs (inherit the regions placing them) + instance/portal files
    for n in az.names:
        if PREFAB_DEF_RE.match(n):
            text = az.read_text(n)
            hits = set(PLANT_RE.findall(text))
            if not hits:
                continue
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

    return loc, portals
