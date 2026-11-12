package com.ratnaraj.herbarium.data;

/**
 * A source plant that yields this plant (from plants-v2.json obtainedFrom.plants),
 * and how: drop on break, shears, salvage.
 */
public record PlantSource(String plantId, boolean drop, boolean shears, boolean salvage) {}
