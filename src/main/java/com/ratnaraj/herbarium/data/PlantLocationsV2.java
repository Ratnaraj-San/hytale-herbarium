package com.ratnaraj.herbarium.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ratnaraj.herbarium.Herbarium;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class PlantLocationsV2 {

    public static final PlantLocationsV2 EMPTY = new PlantLocationsV2(
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList()
    );

    public static final Map<String, List<String>> REGIONS_BY_ZONE = loadRegionsByZone();

    public static List<String> getRegionsForZone(String zoneKey) {
        return REGIONS_BY_ZONE.getOrDefault(zoneKey, List.of());
    }

    private static Map<String, List<String>> loadRegionsByZone() {
        try (InputStream is = PlantLocationsV2.class.getResourceAsStream("/regions-by-zone.json")) {
            if (is == null) {
                return Map.of();
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            Map<String, List<String>> map = new LinkedHashMap<>();

            for (String zone : root.keySet()) {
                List<String> regions = new ArrayList<>();
                for (JsonElement e : root.getAsJsonArray(zone)) {
                    regions.add(e.getAsString());
                }
                map.put(zone, Collections.unmodifiableList(regions));
            }

            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            Herbarium
                    .getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log("[Herbarium] Failed to load regions-by-zone.json: " + e.getMessage())
            ;

            return Map.of();
        }
    }

    private final List<String> regions;
    private final List<String> zones;
    private final List<String> portals;

    public PlantLocationsV2(List<String> regions, List<String> zones, List<String> portals) {
        this.regions = Collections.unmodifiableList(regions);
        this.zones = Collections.unmodifiableList(zones);
        this.portals = Collections.unmodifiableList(portals);
    }

    public List<String> getRegions() {
        return regions;
    }

    public List<String> getZones() {
        return zones;
    }

    public List<String> getPortals() {
        return portals;
    }

    public boolean isEmpty() {
        return regions.isEmpty() && zones.isEmpty() && portals.isEmpty();
    }
}
