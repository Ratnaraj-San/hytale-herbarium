package com.ratnaraj.herbarium.data;

import java.util.Collections;
import java.util.List;

public class PlantLocationsV2 {

    public static final PlantLocationsV2 EMPTY = new PlantLocationsV2(
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList()
    );

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
