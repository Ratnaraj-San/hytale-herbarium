package com.ratnaraj.herbarium.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HerbariumRewards {

    public static final int TIER_SIZE = 50;

    /**
     * Current version of the rewards definition, always "x.y.z".
     * Bump it whenever TIER_REWARDS or COMPENSATIONS change.
     */
    public static final String REWARDS_VERSION = "2.0.0";

    /**
     * One-time compensation grants, hard-coded per rewards version and per
     * tier: version -> tier -> rewards. A grant is claimable only by players
     * who collected the tier before that version existed. Add a new version
     * entry when changing rewards; never edit an existing version (players
     * may not have claimed it yet).
     */
    private static final Map<String, Map<Integer, List<Reward>>> COMPENSATIONS = Map.of(
            "2.0.0", Map.of(
                    1, List.of(),
                    2, List.of(new Reward("Potion_Poison", 4)),
                    3, List.of(new Reward("Potion_Regen_Mana", 4)),
                    4, List.of(),
                    5, List.of(new Reward("Potion_Regen_Health", 4)),
                    6, List.of(new Reward("Potion_Regen_Stamina", 4)),
                    7, List.of(),
                    8, List.of(new Reward("Potion_Mana", 5)),
                    9, List.of()
            )
    );

    /**
     * Compares two "x.y.z" version strings segment by segment
     * ("2.0.10" > "2.0.9"). Returns a negative number if a < b.
     */
    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int segmentA = Integer.parseInt(partsA[i]);
            int segmentB = Integer.parseInt(partsB[i]);
            if (segmentA != segmentB) {
                return Integer.compare(segmentA, segmentB);
            }
        }
        return 0;
    }

    /**
     * A compensation the player is eligible for but has not claimed yet.
     */
    public record CompensationGrant(String version, int tier, List<Reward> rewards) {
    }

    /**
     * Compensation grants still pending for a player: every (version, tier)
     * where the tier was collected at an older rewards version and the grant
     * was never claimed. A player who skipped version "2.0.0"'s compensation
     * will still be offered both the "2.0.0" and "3.0.0" grants later.
     */
    public static List<CompensationGrant> getPendingCompensations(
            Map<Integer, String> collectedTierVersions,
            Map<Integer, Set<String>> claimedCompensations) {
        List<CompensationGrant> pending = new ArrayList<>();

        for (Map.Entry<String, Map<Integer, List<Reward>>> versionEntry : COMPENSATIONS.entrySet()) {
            String version = versionEntry.getKey();
            for (Map.Entry<Integer, List<Reward>> tierEntry : versionEntry.getValue().entrySet()) {
                int tier = tierEntry.getKey();
                String collectedAt = collectedTierVersions.get(tier);
                if (collectedAt != null && compareVersions(collectedAt, version) < 0
                        && !claimedCompensations.getOrDefault(tier, Set.of()).contains(version)) {
                    pending.add(new CompensationGrant(version, tier, tierEntry.getValue()));
                }
            }
        }

        pending.sort(Comparator.comparing(CompensationGrant::version, HerbariumRewards::compareVersions)
                .thenComparingInt(CompensationGrant::tier));

        return pending;
    }

    /**
     * UI state of a tier, to distinguish collected rewards from pending
     * compensation.
     */
    public enum TierState {
        COLLECTED,
        PENDING,
        COMPENSATION_PENDING
    }

    public static TierState getTierState(boolean tierCollected, boolean compensationPending) {
        if (tierCollected) {
            return compensationPending ? TierState.COMPENSATION_PENDING : TierState.COLLECTED;
        }
        return TierState.PENDING;
    }

    public record Reward(String itemId, int quantity) {
    }

    private static final Map<Integer, List<Reward>> TIER_REWARDS = Map.of(
            1, List.of(
                    new Reward("Deco_Kweebec_Plush", 1),
                    new Reward("Ingredient_Charcoal", 50),
                    new Reward("Ingredient_Life_Essence", 50)),
            2, List.of(
                    new Reward("Potion_Poison", 5),
                    new Reward("Ingredient_Flour", 10),
                    new Reward("Ingredient_Life_Essence_Concentrated", 1)),
            3, List.of(
                    new Reward("Potion_Regen_Mana", 5),
                    new Reward("Tool_Trap_Bait", 10),
                    new Reward("Ingredient_Life_Essence_Concentrated", 2)),
            4, List.of(
                    new Reward("Deco_Bat_Plush", 1),
                    new Reward("Ingredient_Poop", 50),
                    new Reward("Ingredient_Life_Essence_Concentrated", 4)),
            5, List.of(
                    new Reward("Potion_Regen_Health", 5),
                    new Reward("Ingredient_Salt", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 8)),
            6, List.of(
                    new Reward("Potion_Regen_Stamina", 5),
                    new Reward("Ingredient_Dough", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 16)),
            7, List.of(
                    new Reward("Deco_Goblin_Plush", 1),
                    new Reward("Ingredient_Spices", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 32)),
            8, List.of(
                    new Reward("Potion_Mana", 5),
                    new Reward("Food_Pie_Pumpkin", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 64)),
            9, List.of(
                    new Reward("Deco_Trophy_Harvest", 1),
                    new Reward("Tool_Repair_Kit_Goblin", 25),
                    new Reward("Ingredient_Life_Essence_Concentrated", 100))
    );

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

    public static List<Reward> getTierRewards(int tier) {
        return TIER_REWARDS.getOrDefault(tier, List.of());
    }

    /**
     * Tiers whose rewards the player has not collected yet.
     */
    public static List<Integer> getPendingRewardTiers(Set<Integer> collectedTiers) {
        List<Integer> pending = new ArrayList<>();

        for (int tier : TIER_REWARDS.keySet()) {
            if (!collectedTiers.contains(tier)) {
                pending.add(tier);
            }
        }

        Collections.sort(pending);

        return pending;
    }
}
