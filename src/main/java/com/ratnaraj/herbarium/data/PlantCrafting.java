package com.ratnaraj.herbarium.data;

import java.util.List;

/**
 * Crafting recipe of a plant (from plants-v2.json obtainedFrom.crafting).
 */
public record PlantCrafting(List<Ingredient> inputs, List<Bench> benches, int outputQuantity) {

    public record Ingredient(String itemId, int quantity) {}

    public record Bench(String id, String itemId, List<String> categories, int requiredTierLevel) {}
}
