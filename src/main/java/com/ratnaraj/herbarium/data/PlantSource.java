package com.ratnaraj.herbarium.data;

/**
 * A source plant that yields this plant (from plants-v2.json obtainedFrom.plants), and how: drop on
 * break, shears, salvage. growth=true means obtained by growing the source; magical=true means the
 * growth requires the crystal fertilizer. Also used for the reverse "gives" relation
 * (plants-v2.json gives), where craft=true means the plant is an ingredient of this plant's
 * crafting recipe (bench = the bench item of that recipe), and collect=true means a harvest
 * interaction (e.g. berries on a bush that grew them).
 */
public record PlantSource(
        String plantId,
        boolean drop,
        boolean shears,
        boolean salvage,
        boolean growth,
        boolean magical,
        boolean craft,
        boolean collect,
        String bench) {}
