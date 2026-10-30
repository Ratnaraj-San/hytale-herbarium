package com.ratnaraj.herbarium;

import java.util.List;
import java.util.Map;

public final class HerbariumRewards {

    /**
     * Number of discoveries per reward tier.
     */
    public static final int TIER_SIZE = 50;

    /**
     * One reward of a tier: an item id with its quantity.
     */
    public record Reward(String itemId, int quantity) {
    }

    /**
     * Items granted by each tier, in display order.
     */
    private static final Map<Integer, List<Reward>> TIER_REWARDS = Map.of(
            1, List.of(
                    new Reward("Deco_Kweebec_Plush", 1),
                    new Reward("Ingredient_Charcoal", 50),
                    new Reward("Ingredient_Life_Essence", 50)),
            2, List.of(
                    new Reward("Potion_Poison", 1),
                    new Reward("Ingredient_Flour", 10),
                    new Reward("Ingredient_Life_Essence_Concentrated", 1)),
            3, List.of(
                    new Reward("Potion_Regen_Mana", 1),
                    new Reward("Tool_Trap_Bait", 10),
                    new Reward("Ingredient_Life_Essence_Concentrated", 2)),
            4, List.of(
                    new Reward("Deco_Bat_Plush", 1),
                    new Reward("Ingredient_Poop", 50),
                    new Reward("Ingredient_Life_Essence_Concentrated", 4)),
            5, List.of(
                    new Reward("Potion_Regen_Health", 1),
                    new Reward("Ingredient_Salt", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 8)),
            6, List.of(
                    new Reward("Potion_Regen_Stamina", 1),
                    new Reward("Ingredient_Dough", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 16)),
            7, List.of(
                    new Reward("Deco_Goblin_Plush", 1),
                    new Reward("Ingredient_Spices", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 32)),
            8, List.of(
                    new Reward("Deco_Trophy_Harvest", 1),
                    new Reward("Food_Pie_Pumpkin", 1),
                    new Reward("Ingredient_Life_Essence_Concentrated", 64))
    );

    private HerbariumRewards() {
    }

    /**
     * Number of discoveries needed to reach a tier.
     */
    public static int getTierThreshold(int tier) {
        return tier * TIER_SIZE;
    }

    /**
     * Number of chest markers to show for a journal of the given size: one
     * per 50 recorded plants, capped by the number of configured tiers.
     */
    public static int getTierCount(int totalPlants) {
        return Math.min(totalPlants / TIER_SIZE, TIER_REWARDS.size());
    }

    /**
     * Items granted by a tier, in display order, or an empty list when the
     * tier has no configured rewards.
     */
    public static List<Reward> getTierRewards(int tier) {
        return TIER_REWARDS.getOrDefault(tier, List.of());
    }
}
