#!/usr/bin/env python3
"""Compare plants/plants-scan.json (fresh scan of the game's Assets.zip) with
src/main/resources/plants.json (curated journal data).

Prints the same recap for both files (total / obtainable / unobtainable), then the
differences: plants only in the scan, plants only in plants.json, and plants whose
obtainableInExplorationMode flag differs (after post-processing the scan, see AGENTS.md).
"""
import sys

from common import PLANTS_JSON, SCAN_FILE, apply_obtainable_rules, flatten, load_json_file, recap


def main():
    scan = flatten(load_json_file(SCAN_FILE))
    curated = flatten(load_json_file(PLANTS_JSON))

    recap("plants-scan.json (raw)", scan)
    apply_obtainable_rules(scan)
    recap("plants-scan.json (post-traitement)", scan)
    recap("plants.json", curated)

    only_scan = sorted(set(scan) - set(curated))
    only_curated = sorted(set(curated) - set(scan))
    flag_diff = sorted(
        plant_id for plant_id in set(scan) & set(curated)
        if bool(scan[plant_id].get("obtainableInExplorationMode")) != bool(curated[plant_id].get("obtainableInExplorationMode"))
    )

    print("[diff]")
    print(f"  only in scan ({len(only_scan)}): {only_scan[:20]}")
    print(f"  only in plants.json ({len(only_curated)}): {only_curated[:20]}")
    print(f"  obtainable flag differs ({len(flag_diff)}): {flag_diff[:20]}")


if __name__ == "__main__":
    sys.exit(main())
