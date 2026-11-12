package com.ratnaraj.herbarium.data;

/**
 * A source plant that yields this plant (from plants-v2.json obtainedFrom.plants),
 * and how: drop on break, shears, salvage. growth=true means obtained by growing the
 * source; magical=true means the growth requires the crystal fertilizer.
 */
public record PlantSource(String plantId, boolean drop, boolean shears, boolean salvage, boolean growth, boolean magical) {}
