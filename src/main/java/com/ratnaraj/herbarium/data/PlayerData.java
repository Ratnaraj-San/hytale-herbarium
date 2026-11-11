package com.ratnaraj.herbarium.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * All progression state of a single player: the plants they discovered,
 * the reward tiers they collected, and whether they already opened the
 * journal. Pure state: no player identity, no catalog, no persistence.
 */
public class PlayerData {

    /**
     * A collected reward tier: when it was collected and at which rewards
     * definition version (used to know which compensation grants apply).
     */
    public record CollectedTier(String collectedDate, String rewardsVersion) {
    }

    private final Set<PlantInfo> plants;
    private final Map<Integer, CollectedTier> collectedRewardTiers;
    private final Map<Integer, Set<String>> claimedCompensations;
    private boolean journalOpened;

    public PlayerData(Set<PlantInfo> plants, Map<Integer, CollectedTier> collectedRewardTiers,
                      Map<Integer, Set<String>> claimedCompensations, boolean journalOpened) {
        this.plants = plants;
        this.collectedRewardTiers = collectedRewardTiers;
        this.claimedCompensations = claimedCompensations;
        this.journalOpened = journalOpened;
    }

    public Set<PlantInfo> getPlants() {
        return Collections.unmodifiableSet(plants);
    }

    public boolean markPlantDiscovered(String plantId, String plantName,
                                       String discoveredDate, String discoveredPlace) {
        PlantInfo existing = getPlantInfo(plantId);
        if (existing != null) {
            if (existing.wasDiscovered()) {
                return false;
            }
            plants.remove(existing);
        }

        plants.add(new PlantInfo(plantId, plantName, true, discoveredDate, discoveredPlace));

        return true;
    }

    public boolean isPlantDiscovered(String plantId) {
        PlantInfo info = getPlantInfo(plantId);
        return info != null && info.wasDiscovered();
    }

    public PlantInfo getPlantInfo(String plantId) {
        for (PlantInfo info : plants) {
            if (info.getId().equals(plantId)) {
                return info;
            }
        }

        return null;
    }

    public int getDiscoveredCount() {
        int count = 0;

        for (PlantInfo plant : plants) {
            if (plant.wasDiscovered()) {
                count++;
            }
        }

        return count;
    }

    public boolean hasOpenedJournal() {
        return journalOpened;
    }

    public void setJournalOpened() {
        journalOpened = true;
    }

    public boolean isRewardTierCollected(int tier) {
        return collectedRewardTiers.containsKey(tier);
    }

    public boolean markRewardTierCollected(int tier, String collectedDate, String rewardsVersion) {
        return collectedRewardTiers.putIfAbsent(tier, new CollectedTier(collectedDate, rewardsVersion)) == null;
    }

    public Map<Integer, CollectedTier> getCollectedRewardTiers() {
        return Collections.unmodifiableMap(collectedRewardTiers);
    }

    /**
     * Tier -> rewards definition version at the time the tier was collected.
     */
    public Map<Integer, String> getCollectedTierVersions() {
        Map<Integer, String> versions = new HashMap<>();
        for (Map.Entry<Integer, CollectedTier> entry : collectedRewardTiers.entrySet()) {
            versions.put(entry.getKey(), entry.getValue().rewardsVersion());
        }
        return versions;
    }

    public boolean markCompensationClaimed(int tier, String version) {
        return claimedCompensations.computeIfAbsent(tier, t -> new HashSet<>()).add(version);
    }

    public Map<Integer, Set<String>> getClaimedCompensations() {
        return Collections.unmodifiableMap(claimedCompensations);
    }

    public void reset() {
        Set<PlantInfo> cleared = new HashSet<>();
        for (PlantInfo plant : plants) {
            cleared.add(new PlantInfo(plant.getId(), plant.getPlantName(), false));
        }
        plants.clear();
        plants.addAll(cleared);
        collectedRewardTiers.clear();
        claimedCompensations.clear();
        journalOpened = false;
    }

    public void unlockAll() {
        Set<PlantInfo> unlocked = new HashSet<>();
        for (PlantInfo plant : plants) {
            unlocked.add(new PlantInfo(plant.getId(), plant.getPlantName(), true));
        }
        plants.clear();
        plants.addAll(unlocked);
    }

    public void unlockCategory(Collection<String> plantIds) {
        Set<PlantInfo> updated = new HashSet<>();

        for (PlantInfo plant : plants) {
            if (plantIds.contains(plant.getId())) {
                updated.add(new PlantInfo(plant.getId(), plant.getPlantName(), true));
            } else {
                updated.add(plant);
            }
        }

        plants.clear();
        plants.addAll(updated);
    }

    public void resetCategory(Collection<String> plantIds) {
        Set<PlantInfo> updated = new HashSet<>();

        for (PlantInfo plant : plants) {
            if (plantIds.contains(plant.getId())) {
                updated.add(new PlantInfo(plant.getId(), plant.getPlantName(), false));
            } else {
                updated.add(plant);
            }
        }

        plants.clear();
        plants.addAll(updated);
    }
}
