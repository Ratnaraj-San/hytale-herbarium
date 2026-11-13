"""Low-level access to the game's Assets.zip + shared regexes/helpers."""
import json
import re
import zipfile
from pathlib import Path

DEFAULT_ASSETS_ZIP = Path(
    "/mnt/ssd/Applications/HypixelStudios/data/Hytale/install/release/package/game/latest/Assets.zip"
)


def resolve_assets_zip(cli_arg=None):
    """CLI arg > HYTALE_ASSETS_ZIP env var > default install path."""
    import os
    if cli_arg:
        return Path(cli_arg)
    if os.environ.get("HYTALE_ASSETS_ZIP"):
        return Path(os.environ["HYTALE_ASSETS_ZIP"])
    return DEFAULT_ASSETS_ZIP

# (?<!\*) excludes block-state patterns like "*Plant_Crop_..._StageFinal" (not player items)
PLANT_RE = re.compile(r"(?<!\*)Plant_[A-Za-z0-9_]+")
# "*<BlockId>_State_Definitions_<State>" scatter patterns (zone covers/masks): the base
# block id is a real plant block (e.g. *Plant_Crop_Wheat_Block_State_Definitions_StageFinal
# -> Plant_Crop_Wheat_Block), so it is kept; the state id itself is not a player item.
WILDCARD_STATE_RE = re.compile(r"\*(Plant_[A-Za-z0-9_]+)_State_Definitions_[A-Za-z0-9_]+")
PLANT_ID_RE = re.compile(r"^Plant_[A-Za-z0-9_]+$")


def plant_ids_in_text(text):
    """Plant ids in raw text: plain ids plus base block ids from wildcard state patterns."""
    found = set(PLANT_RE.findall(text))
    found.update(WILDCARD_STATE_RE.findall(text))
    return found
ZONE_FILE_RE = re.compile(
    r"^Server/World/Default/Zones/([^/]+)/(Custom\.[^/]+\.json|Cave/Covers/[^/]+\.json)$"
)
PREFAB_DEF_RE = re.compile(r"^Server/Prefabs/.+\.prefab\.json$")
ITEM_RE = re.compile(r"^Server/Item/Items/(.+)/([^/]+)\.json$")
SALVAGE_RE = re.compile(r"^Server/Item/Recipes/Salvage/.+\.json$")
DROP_RE = re.compile(r"^Server/Drops/.+/(.+)\.json$")
PORTAL_TYPE_RE = re.compile(r"^Server/PortalTypes/([^/]+)\.json$")
INSTANCE_SCAN_RE = re.compile(
    r"^Server/(EncounterManager|WorldEvent)/.+\.json$|^Server/Instances/.+\.json$|^Server/World/Instance_.+\.json$"
)


class AssetsZip:
    """Read-only accessor for Assets.zip with a JSON cache."""

    def __init__(self, path=None):
        path = Path(path) if path else DEFAULT_ASSETS_ZIP
        self.zip_file = zipfile.ZipFile(path)
        self.entry_names = self.zip_file.namelist()
        self._json_cache = {}

    def load_json(self, entry_name):
        if entry_name not in self._json_cache:
            try:
                with self.zip_file.open(entry_name) as file:
                    self._json_cache[entry_name] = json.loads(file.read().decode("utf-8-sig"))
            except Exception:
                self._json_cache[entry_name] = None
        return self._json_cache[entry_name]

    def read_text(self, entry_name):
        try:
            with self.zip_file.open(entry_name) as file:
                return file.read().decode("utf-8", "replace")
        except Exception:
            return ""


def find_plant_ids(obj):
    """Recursively collect Plant_* strings in block-reference values
    (Type/Solid in covers/assignments, Block in tile/mask files, name in prefabs)."""
    found = set()
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in ("Type", "Solid", "Block", "name"):
                values = value if isinstance(value, list) else [value]
                for item in values:
                    if isinstance(item, str):
                        found.update(plant_ids_in_text(item))
                    else:
                        found |= find_plant_ids(item)  # e.g. Type: [{Block: "Plant_..."}]
            else:
                found |= find_plant_ids(value)
    elif isinstance(obj, list):
        for item in obj:
            found |= find_plant_ids(item)
    return found


def collect_item_ids(obj, out):
    """Recursively collect ItemId values into `out`."""
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key == "ItemId" and isinstance(value, str):
                out.add(value)
            else:
                collect_item_ids(value, out)
    elif isinstance(obj, list):
        for item in obj:
            collect_item_ids(item, out)
