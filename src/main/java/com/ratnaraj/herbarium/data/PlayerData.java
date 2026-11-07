package com.ratnaraj.herbarium.data;

import com.ratnaraj.herbarium.PlantInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * All progression state of a single player: the plants they discovered,
 * the reward tiers they collected, and whether they already opened the
 * journal. Pure state: no player identity, no catalog, no persistence.
 */
public class PlayerData {

    private final Set<PlantInfo> plants;
    private final Map<Integer, String> collectedRewardTiers;
    private boolean journalOpened;

    public PlayerData(Set<PlantInfo> plants, Map<Integer, String> collectedRewardTiers,
                      boolean journalOpened) {
        this.plants = plants;
        this.collectedRewardTiers = collectedRewardTiers;
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

    public boolean markRewardTierCollected(int tier, String collectedDate) {
        return collectedRewardTiers.putIfAbsent(tier, collectedDate) == null;
    }

    public Map<Integer, String> getCollectedRewardTiers() {
        return Collections.unmodifiableMap(collectedRewardTiers);
    }

    public void reset() {
        Set<PlantInfo> cleared = new HashSet<>();
        for (PlantInfo plant : plants) {
            cleared.add(new PlantInfo(plant.getId(), plant.getPlantName(), false));
        }
        plants.clear();
        plants.addAll(cleared);
        collectedRewardTiers.clear();
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
}
