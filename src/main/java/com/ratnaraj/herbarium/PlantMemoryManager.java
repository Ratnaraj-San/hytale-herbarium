package com.ratnaraj.herbarium;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Player progression: the plant universe from plants.json (obtainable in
 * Exploration Mode only), the plants each player discovered, their collected
 * reward tiers, persisted as one save file per player.
 */
public class PlantMemoryManager {

    private final Map<String, PlantInfo> allPlantsFromConfig;

    // Generation locations of each tracked plant, as translation keys
    private final Map<String, List<String>> plantLocations = new HashMap<>();

    // Plant ids per category, preserving plants.json order
    private final Map<String, List<String>> plantCategories = new LinkedHashMap<>();

    private final Map<UUID, Set<PlantInfo>> playerMemories = new ConcurrentHashMap<>();

    private final Map<UUID, Map<Integer, String>> collectedRewardTiers = new ConcurrentHashMap<>();

    private final Set<UUID> playersWhoOpenedJournal = ConcurrentHashMap.newKeySet();

    private Path dataDirectory;

    public PlantMemoryManager() {
        this.allPlantsFromConfig = loadPlantsFromConfig();
    }

    public List<String> getPlantLocations(String plantId) {
        return plantLocations.getOrDefault(plantId, Collections.emptyList());
    }

    private Map<String, PlantInfo> loadPlantsFromConfig() {
        Map<String, PlantInfo> plants = new LinkedHashMap<>();
        try {
            InputStream is = getClass().getResourceAsStream("/plants.json");
            if (is != null) {
                JsonObject config = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                for (String category : config.keySet()) {
                    JsonArray items = config.getAsJsonArray(category);
                    List<String> categoryPlants = new ArrayList<>();
                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        String id = item.get("id").getAsString();

                        boolean obtainable = !item.has("obtainableInExplorationMode")
                                || item.get("obtainableInExplorationMode").getAsBoolean();
                        if (!obtainable) {
                            continue;
                        }

                        plants.put(id, new PlantInfo(id, "", false));
                        categoryPlants.add(id);

                        List<String> locations = new ArrayList<>();
                        if (item.has("locations")) {
                            for (JsonElement location : item.getAsJsonArray("locations")) {
                                locations.add(location.getAsString());
                            }
                        }
                        plantLocations.put(id, Collections.unmodifiableList(locations));
                    }
                    if (!categoryPlants.isEmpty()) {
                        plantCategories.put(category, Collections.unmodifiableList(categoryPlants));
                    }
                }
                Herbarium.getInstance().getLogger().at(Level.INFO).log(
                    "[Herbarium] Loaded " + plants.size() + " obtainable plants from plants.json"
                );
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Failed to load plants.json: " + e.getMessage()
            );
        }
        return Collections.unmodifiableMap(plants);
    }

    public void initialize(Path pluginDataDir) {
        this.dataDirectory = pluginDataDir;

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.SEVERE).log(
                "[Herbarium] Error creating data directory: " + e.getMessage()
            );
        }
    }

    public boolean hasOpenedJournal(PlayerRef playerRef) {
        return playerRef != null && playersWhoOpenedJournal.contains(playerRef.getUuid());
    }

    /**
     * Persists the journal-opened flag, so the intro modal is only shown on
     * the very first opening.
     */
    public void markJournalOpened(PlayerRef playerRef) {
        if (playerRef != null && playersWhoOpenedJournal.add(playerRef.getUuid())) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            savePlayerMemories(playerId, memories);
        }
    }

    public void loadAllMemories() {
        if (dataDirectory == null || !Files.exists(dataDirectory)) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Data directory not initialized, loading cancelled."
            );
            return;
        }
        
        int loadedCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory, "*.json")) {
            for (Path file : stream) {
                try {
                    String playerId = file.getFileName().toString().replace(".json", "");
                    UUID uuid = UUID.fromString(playerId);
                    Set<PlantInfo> plants = loadPlayerMemories(uuid);
                    if (plants != null && !plants.isEmpty()) {
                        playerMemories.put(uuid, plants);
                        loadedCount++;
                    }
                } catch (IllegalArgumentException e) {
                    Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                        "[Herbarium] Invalid memory file: " + file.getFileName()
                    );
                } catch (Exception e) {
                    Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                        "[Herbarium] Error loading " + file.getFileName() + ": " + e.getMessage()
                    );
                }
            }
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.SEVERE).log(
                "[Herbarium] Error loading memories: " + e.getMessage()
            );
        }
        
        Herbarium.getInstance().getLogger().at(Level.INFO).log(
            "[Herbarium] " + loadedCount + " player memories loaded."
        );
    }

    /**
     * Loads a player's memories from their file: the journalOpened flag,
     * collected reward tiers and plant memories.
     */
    private Set<PlantInfo> loadPlayerMemories(UUID playerId) {
        Path file = getPlayerFile(playerId);

        if (!Files.exists(file)) {
            return initializePlayerMemories(playerId);
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            String fileContent = content.toString().trim();
            if (fileContent.isEmpty()) {
                return initializePlayerMemories(playerId);
            }

            JsonElement root = JsonParser.parseString(fileContent);
            Set<PlantInfo> plants = new HashSet<>();

            JsonObject rootObject = root.getAsJsonObject();
            if (rootObject.has("journalOpened") && rootObject.get("journalOpened").getAsBoolean()) {
                playersWhoOpenedJournal.add(playerId);
            }
            for (JsonElement element : rootObject.getAsJsonArray("plants")) {
                plants.add(parsePlantMemory(element));
            }
            if (rootObject.has("collectedRewardTiers")) {
                for (JsonElement element : rootObject.getAsJsonArray("collectedRewardTiers")) {
                    try {
                        JsonObject entry = element.getAsJsonObject();
                        String collectedDate = entry.has("collectedDate")
                                && !entry.get("collectedDate").isJsonNull()
                                ? entry.get("collectedDate").getAsString() : "";
                        getCollectedRewardTiers(playerId).put(entry.get("tier").getAsInt(), collectedDate);
                    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
                        Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                            "[Herbarium] Invalid collected reward tier for " + playerId + ": " + element
                        );
                    }
                }
            }

            // Drop memories of plants the journal no longer tracks
            plants.removeIf(plant -> !allPlantsFromConfig.containsKey(plant.getId()));

            return plants;
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Error reading file for " + playerId + ": " + e.getMessage()
            );
            return initializePlayerMemories(playerId);
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Corrupted memory file for " + playerId + ", reinitializing"
            );
            return initializePlayerMemories(playerId);
        }
    }

    private PlantInfo parsePlantMemory(JsonElement element) {
        JsonObject jsonObject = element.getAsJsonObject();
        String id = jsonObject.get("id").getAsString();
        boolean wasDiscovered = jsonObject.get("wasDiscovered").getAsBoolean();
        String discoveredDate = jsonObject.has("discoveredDate") && !jsonObject.get("discoveredDate").isJsonNull()
                ? jsonObject.get("discoveredDate").getAsString() : null;
        String discoveredPlace = jsonObject.has("discoveredPlace") && !jsonObject.get("discoveredPlace").isJsonNull()
                ? jsonObject.get("discoveredPlace").getAsString() : null;
        return new PlantInfo(id, "", wasDiscovered, discoveredDate, discoveredPlace);
    }
    
    private Set<PlantInfo> initializePlayerMemories(UUID playerId) {
        Set<PlantInfo> plants = new HashSet<>();

        for (PlantInfo plant : allPlantsFromConfig.values()) {
            plants.add(new PlantInfo(plant.getId(), "", false));
        }

        return plants;
    }

    private void savePlayerMemories(UUID playerId, Set<PlantInfo> plants) {
        Path file = getPlayerFile(playerId);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            JsonObject root = new JsonObject();
            root.addProperty("journalOpened", playersWhoOpenedJournal.contains(playerId));

            Map<Integer, String> collected = new TreeMap<>(getCollectedRewardTiers(playerId));
            JsonArray collectedArray = new JsonArray();
            for (Map.Entry<Integer, String> entry : collected.entrySet()) {
                JsonObject collectedEntry = new JsonObject();
                collectedEntry.addProperty("tier", entry.getKey());
                collectedEntry.addProperty("collectedDate", entry.getValue());
                collectedArray.add(collectedEntry);
            }
            root.add("collectedRewardTiers", collectedArray);

            JsonArray jsonArray = new JsonArray();
            for (PlantInfo plant : plants) {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("id", plant.getId());
                jsonObject.addProperty("wasDiscovered", plant.wasDiscovered());
                jsonObject.addProperty("discoveredDate", plant.getDiscoveredDate());
                jsonObject.addProperty("discoveredPlace", plant.getDiscoveredPlace());
                jsonArray.add(jsonObject);
            }
            root.add("plants", jsonArray);
            writer.write(gson.toJson(root));
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.SEVERE).log(
                "[Herbarium] Error saving memories for " + playerId + ": " + e.getMessage()
            );
        }
    }

    private Path getPlayerFile(UUID playerId) {
        return dataDirectory.resolve(playerId.toString() + ".json");
    }

    public void saveAllMemories() {
        if (dataDirectory == null || !Files.exists(dataDirectory)) {
            return;
        }
        
        int savedCount = 0;
        for (Map.Entry<UUID, Set<PlantInfo>> entry : playerMemories.entrySet()) {
            savePlayerMemories(entry.getKey(), entry.getValue());
            savedCount++;
        }
        
        Herbarium.getInstance().getLogger().at(Level.INFO).log(
            "[Herbarium] " + savedCount + " player memories saved."
        );
    }

    public boolean isPlantItem(String itemId) {
        if (itemId == null) return false;

        return itemId.startsWith("Plant_");
    }

    public boolean addPlantMemory(PlayerRef playerRef, String plantId, String plantName,
                                  String discoveredDate, String discoveredPlace) {
        if (playerRef == null || plantId == null || !isPlantItem(plantId)) {
            return false;
        }

        if (!allPlantsFromConfig.containsKey(plantId)) {
            return false;
        }

        UUID playerId = playerRef.getUuid();
        Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));

        boolean isNew = false;
        PlantInfo existingInfo = null;

        for (PlantInfo info : memories) {
            if (info.getId().equals(plantId)) {
                existingInfo = info;
                break;
            }
        }

        if (existingInfo != null) {
            if (!existingInfo.wasDiscovered()) {
                memories.remove(existingInfo);
                memories.add(new PlantInfo(plantId, plantName, true, discoveredDate, discoveredPlace));
                isNew = true;
            }
        } else {
            // Should not happen if initialized properly, but handle it
            memories.add(new PlantInfo(plantId, plantName, true, discoveredDate, discoveredPlace));
            isNew = true;
        }

        if (isNew) {
            savePlayerMemories(playerId, memories);
        }
        
        return isNew;
    }

    public Set<PlantInfo> getPlayerMemories(PlayerRef playerRef) {
        if (playerRef == null) {
            return Collections.emptySet();
        }
        
        return Collections.unmodifiableSet(
            playerMemories.getOrDefault(playerRef.getUuid(), Collections.emptySet())
        );
    }

    public int getMemoryCount(PlayerRef playerRef) {
        return (int) getPlayerMemories(playerRef).stream().filter(PlantInfo::wasDiscovered).count();
    }

    public int getTotalPlantCount() {
        return allPlantsFromConfig.size();
    }

    private Map<Integer, String> getCollectedRewardTiers(UUID playerId) {
        return collectedRewardTiers.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    }

    public boolean isRewardTierCollected(PlayerRef playerRef, int tier) {
        return playerRef != null && getCollectedRewardTiers(playerRef.getUuid()).containsKey(tier);
    }

    public void markRewardTierCollected(PlayerRef playerRef, int tier) {
        if (playerRef == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        Map<Integer, String> collected = getCollectedRewardTiers(playerId);
        if (!collected.containsKey(tier)) {
            collected.put(tier, Instant.now().toString());
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            savePlayerMemories(playerId, memories);
        }
    }

    public void clearPlayerMemories(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));

            Set<PlantInfo> clearedMemories = new HashSet<>();
            for (PlantInfo plant : memories) {
                clearedMemories.add(new PlantInfo(plant.getId(), plant.getPlantName(), false));
            }

            playerMemories.put(playerId, clearedMemories);
            getCollectedRewardTiers(playerId).clear();
            savePlayerMemories(playerId, clearedMemories);

            // Also forget the journal was opened, so the intro shows again
            playersWhoOpenedJournal.remove(playerId);
        }
    }

    public void unlockAllPlayerMemories(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));

            Set<PlantInfo> unlockedMemories = new HashSet<>();
            for (PlantInfo plant : memories) {
                unlockedMemories.add(new PlantInfo(plant.getId(), plant.getPlantName(), true));
            }

            playerMemories.put(playerId, unlockedMemories);
            savePlayerMemories(playerId, unlockedMemories);
        }
    }

    public Map<String, List<String>> getPlantCategories() {
        return Collections.unmodifiableMap(plantCategories);
    }

    public List<String> getPlantsInCategory(String category) {
        return plantCategories.getOrDefault(category, Collections.emptyList());
    }

    public boolean isPlantDiscovered(PlayerRef playerRef, String plantId) {
        for (PlantInfo info : getPlayerMemories(playerRef)) {
            if (info.getId().equals(plantId)) {
                return info.wasDiscovered();
            }
        }
        return false;
    }

    public PlantInfo getPlayerPlantInfo(PlayerRef playerRef, String plantId) {
        for (PlantInfo info : getPlayerMemories(playerRef)) {
            if (info.getId().equals(plantId)) {
                return info;
            }
        }
        return null;
    }

    public String getPlantIcon(String plantId) {
        try {
            Item item = Item.getAssetMap().getAsset(plantId);
            if (item != null && item.getIcon() != null && !item.getIcon().isEmpty()) {
                return item.getIcon();
            }
        } catch (Exception e) {
            // Plant has no item asset; no icon available.
        }
        return null;
    }

    public Message getPlantNameMessage(String plantId) {
        String translationKey = getPlantTranslationKey(plantId);
        if (translationKey != null) {
            return Message.translation(translationKey);
        }
        return Message.raw(plantId);
    }

    public String getPlantTranslationKey(String plantId) {
        try {
            Item item = Item.getAssetMap().getAsset(plantId);
            if (item != null && item.getTranslationProperties() != null) {
                return item.getTranslationProperties().getName();
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Failed to get translation key for " + plantId + ": " + e.getMessage()
            );
        }
        return null;
    }
}
