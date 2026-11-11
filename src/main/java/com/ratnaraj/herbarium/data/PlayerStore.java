package com.ratnaraj.herbarium.data;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ratnaraj.herbarium.Herbarium;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The progression of every player known to the server, kept in memory as
 * PlayerData entries. The PlayerRef-facing API mutates a player's
 * PlayerData and re-saves it when something changed; the per-player file
 * persistence is delegated to PlayerFileStore.
 */
public class PlayerStore {

    private final PlantCatalogV2 catalog;
    private final PlayerFileStore fileStore;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    public PlayerStore(PlantCatalogV2 catalog) {
        this.catalog = catalog;
        this.fileStore = new PlayerFileStore(catalog);
    }

    public void initialize(Path pluginDataDir) {
        fileStore.initialize(pluginDataDir);
    }

    public void loadAllMemories() {
        players.putAll(fileStore.loadAll());

        Herbarium.getInstance().getLogger().at(Level.INFO).log(
                "[Herbarium] " + players.size() + " player memories loaded."
        );
    }

    public void saveAllMemories() {
        if (!fileStore.isInitialized()) {
            return;
        }

        int savedCount = 0;
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            fileStore.save(entry.getKey(), entry.getValue());
            savedCount++;
        }

        Herbarium.getInstance().getLogger().at(Level.INFO).log(
                "[Herbarium] " + savedCount + " player memories saved."
        );
    }

    private PlayerData getOrCreate(UUID playerId) {
        return players.computeIfAbsent(playerId, fileStore::loadPlayer);
    }

    // ------------------------------------------------------------------
    // Player-facing API
    // ------------------------------------------------------------------

    public boolean hasOpenedJournal(PlayerRef playerRef) {
        return playerRef != null && getOrCreate(playerRef.getUuid()).hasOpenedJournal();
    }

    /**
     * Persists the journal-opened flag, so the intro modal is only shown on
     * the very first opening.
     */
    public void markJournalOpened(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        if (!data.hasOpenedJournal()) {
            data.setJournalOpened();
            fileStore.save(playerRef.getUuid(), data);
        }
    }

    public boolean addPlantMemory(PlayerRef playerRef, String plantId, String plantName,
                                  String discoveredDate, String discoveredPlace) {
        if (playerRef == null || plantId == null || !catalog.isPlantItem(plantId)) {
            return false;
        }
        if (!catalog.getAllPlants().containsKey(plantId)) {
            return false;
        }

        PlayerData data = getOrCreate(playerRef.getUuid());
        boolean isNew = data.markPlantDiscovered(plantId, plantName, discoveredDate, discoveredPlace);
        if (isNew) {
            fileStore.save(playerRef.getUuid(), data);
        }
        return isNew;
    }

    public Set<PlantInfo> getPlayerMemories(PlayerRef playerRef) {
        if (playerRef == null) {
            return Collections.emptySet();
        }
        return getOrCreate(playerRef.getUuid()).getPlants();
    }

    public int getMemoryCount(PlayerRef playerRef) {
        return playerRef == null ? 0 : getOrCreate(playerRef.getUuid()).getDiscoveredCount();
    }

    public boolean isPlantDiscovered(PlayerRef playerRef, String plantId) {
        return playerRef != null && getOrCreate(playerRef.getUuid()).isPlantDiscovered(plantId);
    }

    public PlantInfo getPlayerPlantInfo(PlayerRef playerRef, String plantId) {
        return playerRef == null ? null : getOrCreate(playerRef.getUuid()).getPlantInfo(plantId);
    }

    public boolean isRewardTierCollected(PlayerRef playerRef, int tier) {
        return playerRef != null && getOrCreate(playerRef.getUuid()).isRewardTierCollected(tier);
    }

    public void markRewardTierCollected(PlayerRef playerRef, int tier) {
        if (playerRef == null) {
            return;
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        if (data.markRewardTierCollected(tier, Instant.now().toString(), HerbariumRewards.rewardsVersion())) {
            fileStore.save(playerRef.getUuid(), data);
        }
    }

    public List<HerbariumRewards.CompensationGrant> getPendingCompensations(PlayerRef playerRef) {
        if (playerRef == null) {
            return List.of();
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        return HerbariumRewards.getPendingCompensations(
                data.getCollectedTierVersions(), data.getClaimedCompensations());
    }

    public void markCompensationClaimed(PlayerRef playerRef, int tier, String version) {
        if (playerRef == null) {
            return;
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        if (data.markCompensationClaimed(tier, version)) {
            fileStore.save(playerRef.getUuid(), data);
        }
    }

    public void clearPlayerMemories(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        data.reset();
        fileStore.save(playerRef.getUuid(), data);
    }

    public void unlockAllPlayerMemories(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }
        PlayerData data = getOrCreate(playerRef.getUuid());
        data.unlockAll();
        fileStore.save(playerRef.getUuid(), data);
    }
}
