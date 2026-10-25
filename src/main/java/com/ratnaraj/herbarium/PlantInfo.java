package com.ratnaraj.herbarium;

public class PlantInfo {
    private final String id;
    private final String plantName;
    private final boolean wasDiscovered;
    private final String discoveredDate;
    private final String discoveredPlace;

    public PlantInfo(String id, String plantName, boolean wasDiscovered) {
        this(id, plantName, wasDiscovered, null, null);
    }

    public PlantInfo(String id, String plantName, boolean wasDiscovered,
                     String discoveredDate, String discoveredPlace) {
        this.id = id;
        this.plantName = plantName;
        this.wasDiscovered = wasDiscovered;
        this.discoveredDate = discoveredDate;
        this.discoveredPlace = discoveredPlace;
    }

    public String getId() {
        return id;
    }

    public String getPlantName() {
        return plantName;
    }

    public boolean wasDiscovered() {
        return wasDiscovered;
    }

    /**
     * ISO-8601 instant of the discovery, or null if unknown or not discovered.
     */
    public String getDiscoveredDate() {
        return discoveredDate;
    }

    /**
     * Human-readable coordinates of the discovery, or null if unknown.
     */
    public String getDiscoveredPlace() {
        return discoveredPlace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlantInfo that = (PlantInfo) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
