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
PLANT_ID_RE = re.compile(r"^Plant_[A-Za-z0-9_]+$")
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
        self.zf = zipfile.ZipFile(path)
        self.names = self.zf.namelist()
        self._cache = {}

    def load_json(self, name):
        if name not in self._cache:
            try:
                with self.zf.open(name) as f:
                    self._cache[name] = json.loads(f.read().decode("utf-8-sig"))
            except Exception:
                self._cache[name] = None
        return self._cache[name]

    def read_text(self, name):
        try:
            with self.zf.open(name) as f:
                return f.read().decode("utf-8", "replace")
        except Exception:
            return ""


def find_plant_ids(obj):
    """Recursively collect Plant_* strings in block-reference values
    (Type/Solid in covers/assignments, Block in tile/mask files, name in prefabs)."""
    found = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in ("Type", "Solid", "Block", "name"):
                vals = v if isinstance(v, list) else [v]
                for x in vals:
                    if isinstance(x, str):
                        found.update(PLANT_RE.findall(x))
                    else:
                        found |= find_plant_ids(x)  # e.g. Type: [{Block: "Plant_..."}]
            else:
                found |= find_plant_ids(v)
    elif isinstance(obj, list):
        for x in obj:
            found |= find_plant_ids(x)
    return found


def collect_item_ids(obj, out):
    """Recursively collect ItemId values into `out`."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "ItemId" and isinstance(v, str):
                out.add(v)
            else:
                collect_item_ids(v, out)
    elif isinstance(obj, list):
        for x in obj:
            collect_item_ids(x, out)
