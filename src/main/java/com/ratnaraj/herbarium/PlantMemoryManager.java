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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manager for plant memories (player progression).
 * Loads the journal reference from plants.json: the plant universe grouped by
 * category, with each plant's Exploration Mode obtainability and generation
 * locations. The journal only tracks plants obtainable in Exploration Mode.
 * Uses the Hytale API to verify items and fetch display names. Stores plants
 * discovered by each player with persistence: a single save file per player
 * holding the plant memories and the journal-opened flag.
 */
public class PlantMemoryManager {

    // All tracked plants from plants.json as reference (obtainable only)
    private final Map<String, PlantInfo> allPlantsFromConfig;

    // Generation locations of each tracked plant, as translation keys
    private final Map<String, List<String>> plantLocations = new HashMap<>();

    // Plant ids per category, preserving plants.json order
    private final Map<String, List<String>> plantCategories = new LinkedHashMap<>();
    
    // Plant memories per player (UUID -> Set of PlantInfo)
    private final Map<UUID, Set<PlantInfo>> playerMemories = new ConcurrentHashMap<>();
    
    // Reward tiers already claimed per player (UUID -> tier numbers)
    private final Map<UUID, Set<Integer>> claimedRewardTiers = new ConcurrentHashMap<>();
    
    // Players who have already opened the journal at least once (intro shown)
    private final Set<UUID> playersWhoOpenedJournal = ConcurrentHashMap.newKeySet();
    
    // Data storage directory
    private Path dataDirectory;

    /**
     * Constructor - Loads the journal reference from plants.json.
     */
    public PlantMemoryManager() {
        this.allPlantsFromConfig = loadPlantsFromConfig();
    }

    /**
     * Gets the known locations of a plant, or an empty list if unknown.
     */
    public List<String> getPlantLocations(String plantId) {
        return plantLocations.getOrDefault(plantId, Collections.emptyList());
    }

    /**
     * Loads the journal reference from plants.json: the plant universe
     * grouped by category, with each plant's Exploration Mode obtainability
     * and generation locations. Plants marked unobtainable in Exploration
     * Mode are not tracked; empty categories are left out entirely.
     */
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

                        // The journal only tracks plants obtainable in Exploration Mode
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

    /**
     * Checks whether a plant is tracked by the journal, i.e. obtainable in
     * Exploration Mode. The universe is filtered at load time, so this is
     * simply membership in the journal reference.
     */
    public boolean isPlantObtainable(String plantId) {
        return allPlantsFromConfig.containsKey(plantId);
    }

    /**
     * Initializes the manager with the storage directory.
     */
    public void initialize(Path pluginDataDir) {
        this.dataDirectory = pluginDataDir;

        try {
            Files.createDirectories(dataDirectory);
            migrateLegacyDataDirectory();
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.SEVERE).log(
                "[Herbarium] Error creating data directory: " + e.getMessage()
            );
        }

        loadJournalOpened();
    }

    /**
     * Name of the legacy subdirectory that used to hold the save files, when
     * the data directory was "mods/Herbarium/herbarium/". Its files are moved
     * directly into the data directory, then the empty subdirectory removed.
     */
    private static final String LEGACY_DATA_DIRECTORY = "herbarium";

    /**
     * Moves the save files from the legacy "herbarium" subdirectory into the
     * data directory, then deletes the subdirectory if it is empty.
     */
    private void migrateLegacyDataDirectory() throws IOException {
        Path legacyDir = dataDirectory.resolve(LEGACY_DATA_DIRECTORY);
        if (!Files.isDirectory(legacyDir)) {
            return;
        }

        int moved = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyDir, "*.json")) {
            for (Path file : stream) {
                Path target = dataDirectory.resolve(file.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.move(file, target);
                    moved++;
                }
            }
        }

        try {
            Files.deleteIfExists(legacyDir);
            Herbarium.getInstance().getLogger().at(Level.INFO).log(
                "[Herbarium] Moved " + moved + " save file(s) from the legacy "
                        + LEGACY_DATA_DIRECTORY + " subdirectory"
            );
        } catch (DirectoryNotEmptyException e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Legacy " + LEGACY_DATA_DIRECTORY
                        + " subdirectory still contains unexpected files, leaving it in place"
            );
        }
    }

    /**
     * Name of the legacy shared file storing the players who already opened
     * the journal. It is read once to migrate old saves to the per-player
     * format, then deleted once its data has been merged.
     */
    private static final String JOURNAL_OPENED_FILE = "journal_opened.json";

    /**
     * Gets the file path of the journal-opened tracking file.
     */
    private Path getJournalOpenedFile() {
        return dataDirectory.resolve(JOURNAL_OPENED_FILE);
    }

    /**
     * Loads the set of players who already opened the journal from the legacy
     * shared file. Only kept to migrate old saves: the flag now lives in each
     * player's own save file.
     */
    private void loadJournalOpened() {
        Path file = getJournalOpenedFile();
        if (!Files.exists(file)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            JsonArray jsonArray = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : jsonArray) {
                try {
                    playersWhoOpenedJournal.add(UUID.fromString(element.getAsString()));
                } catch (IllegalArgumentException e) {
                    Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                        "[Herbarium] Invalid player id in " + JOURNAL_OPENED_FILE + ": " + element.getAsString()
                    );
                }
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                "[Herbarium] Error loading " + JOURNAL_OPENED_FILE + ": " + e.getMessage()
            );
        }
    }

    /**
     * Checks whether the player has already opened the journal at least once.
     */
    public boolean hasOpenedJournal(PlayerRef playerRef) {
        return playerRef != null && playersWhoOpenedJournal.contains(playerRef.getUuid());
    }

    /**
     * Marks the journal as opened by the player and persists it in the
     * player's save file, so the intro modal is only shown on the very
     * first opening.
     */
    public void markJournalOpened(PlayerRef playerRef) {
        if (playerRef != null && playersWhoOpenedJournal.add(playerRef.getUuid())) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            savePlayerMemories(playerId, memories);
        }
    }

    /**
     * Loads all memories from files.
     */
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
                // Skip the journal-opened tracking file, it is not a player file
                if (file.getFileName().toString().equals(JOURNAL_OPENED_FILE)) {
                    continue;
                }
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

        migrateLegacyJournalOpenedFile();
    }

    /**
     * Migrates old saves to the single per-player file format: re-saves every
     * player file with its journalOpened flag, then deletes the legacy shared
     * journal_opened.json once all its data has been merged.
     */
    private void migrateLegacyJournalOpenedFile() {
        if (!Files.exists(getJournalOpenedFile())) {
            return;
        }

        for (UUID playerId : playersWhoOpenedJournal) {
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            savePlayerMemories(playerId, memories);
        }
        for (Map.Entry<UUID, Set<PlantInfo>> entry : playerMemories.entrySet()) {
            savePlayerMemories(entry.getKey(), entry.getValue());
        }

        try {
            Files.deleteIfExists(getJournalOpenedFile());
            Herbarium.getInstance().getLogger().at(Level.INFO).log(
                "[Herbarium] Merged legacy " + JOURNAL_OPENED_FILE + " into the per-player save files"
            );
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.SEVERE).log(
                "[Herbarium] Error deleting " + JOURNAL_OPENED_FILE + ": " + e.getMessage()
            );
        }
    }

    /**
     * Loads a specific player's memories from their file.
     * The current format is a JSON object holding the journalOpened flag and
     * the plant memories; the legacy format was a plain JSON array of plant
     * memories, with the journalOpened flag in journal_opened.json.
     * If the file doesn't exist, initializes with all plants from plants.json (not discovered).
     */
    private Set<PlantInfo> loadPlayerMemories(UUID playerId) {
        Path file = getPlayerFile(playerId);

        if (!Files.exists(file)) {
            // Initialize with all plants from config (not discovered)
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

            if (root.isJsonObject()) {
                // Current format: journalOpened flag + plant memories
                JsonObject rootObject = root.getAsJsonObject();
                if (rootObject.has("journalOpened") && rootObject.get("journalOpened").getAsBoolean()) {
                    playersWhoOpenedJournal.add(playerId);
                }
                for (JsonElement element : rootObject.getAsJsonArray("plants")) {
                    plants.add(parsePlantMemory(element));
                }
                if (rootObject.has("claimedRewardTiers")) {
                    Set<Integer> claimed = new HashSet<>();
                    for (JsonElement element : rootObject.getAsJsonArray("claimedRewardTiers")) {
                        try {
                            claimed.add(element.getAsInt());
                        } catch (ClassCastException | IllegalStateException e) {
                            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                                "[Herbarium] Invalid claimed reward tier for " + playerId + ": " + element
                            );
                        }
                    }
                    if (!claimed.isEmpty()) {
                        getClaimedRewardTiers(playerId).addAll(claimed);
                    }
                }
            } else {
                // Legacy format: plain array of plant memories
                for (JsonElement element : root.getAsJsonArray()) {
                    plants.add(parsePlantMemory(element));
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

    /**
     * Parses one plant memory entry of a player save file.
     */
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
    
    /**
     * Initializes a new player with all plants from plants.json (not discovered).
     */
    private Set<PlantInfo> initializePlayerMemories(UUID playerId) {
        Set<PlantInfo> plants = new HashSet<>();
        
        // Use plants.json as reference if available
        if (!allPlantsFromConfig.isEmpty()) {
            for (PlantInfo plant : allPlantsFromConfig.values()) {
                plants.add(new PlantInfo(plant.getId(), "", false));
            }
        } else {
            // Fallback: use plants from config
            for (String id : allPlantsFromConfig.keySet()) {
                plants.add(new PlantInfo(id, "", false));
            }
        }
        
        return plants;
    }

    /**
     * Saves a player's memories to their file: the journalOpened flag and
     * all plants with their wasDiscovered status, discovery date and place.
     */
    private void savePlayerMemories(UUID playerId, Set<PlantInfo> plants) {
        Path file = getPlayerFile(playerId);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            JsonObject root = new JsonObject();
            root.addProperty("journalOpened", playersWhoOpenedJournal.contains(playerId));

            List<Integer> claimed = new ArrayList<>(getClaimedRewardTiers(playerId));
            Collections.sort(claimed);
            JsonArray claimedArray = new JsonArray();
            for (int tier : claimed) {
                claimedArray.add(tier);
            }
            root.add("claimedRewardTiers", claimedArray);

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

    /**
     * Gets the file path for a player.
     */
    private Path getPlayerFile(UUID playerId) {
        return dataDirectory.resolve(playerId.toString() + ".json");
    }

    /**
     * Saves all memories to disk.
     */
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

    /**
     * Checks if an item is a Plant item using Hytale API.
     */
    public boolean isPlantItem(String itemId) {
        if (itemId == null) return false;

        return itemId.startsWith("Plant_");
    }

    /**
     * Adds a plant to a player's memory and automatically saves.
     * Updates wasDiscovered status for the plant.
     */
    public boolean addPlantMemory(PlayerRef playerRef, String plantId, String plantName) {
        return addPlantMemory(playerRef, plantId, plantName, null, null);
    }

    /**
     * Adds a plant to a player's memory with the discovery date and place,
     * then automatically saves.
     */
    public boolean addPlantMemory(PlayerRef playerRef, String plantId, String plantName,
                                  String discoveredDate, String discoveredPlace) {
        if (playerRef == null || plantId == null || !isPlantItem(plantId)) {
            return false;
        }

        // The journal only tracks plants obtainable in Exploration Mode
        if (!allPlantsFromConfig.containsKey(plantId)) {
            return false;
        }

        UUID playerId = playerRef.getUuid();
        Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));

        // Find the plant in memories and update its discovered status
        boolean isNew = false;
        PlantInfo existingInfo = null;

        for (PlantInfo info : memories) {
            if (info.getId().equals(plantId)) {
                existingInfo = info;
                break;
            }
        }

        if (existingInfo != null) {
            // Only update and report new if the plant wasn't already discovered
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

    /**
     * Gets all plants discovered by a player.
     */
    public Set<PlantInfo> getPlayerMemories(PlayerRef playerRef) {
        if (playerRef == null) {
            return Collections.emptySet();
        }
        
        return Collections.unmodifiableSet(
            playerMemories.getOrDefault(playerRef.getUuid(), Collections.emptySet())
        );
    }

    /**
     * Gets the total number of plants discovered by a player.
     */
    public int getMemoryCount(PlayerRef playerRef) {
        return (int) getPlayerMemories(playerRef).stream().filter(PlantInfo::wasDiscovered).count();
    }

    /**
     * Gets the total number of available plants from plants.json.
     */
    public int getTotalPlantCount() {
        return allPlantsFromConfig.size();
    }

    /**
     * Gets the claimed reward tiers of a player, creating the set on first
     * access.
     */
    private Set<Integer> getClaimedRewardTiers(UUID playerId) {
        return claimedRewardTiers.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
    }

    public boolean isRewardTierClaimed(PlayerRef playerRef, int tier) {
        return playerRef != null && getClaimedRewardTiers(playerRef.getUuid()).contains(tier);
    }

    public void markRewardTierClaimed(PlayerRef playerRef, int tier) {
        if (playerRef == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (getClaimedRewardTiers(playerId).add(tier)) {
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            savePlayerMemories(playerId, memories);
        }
    }

    /**
     * Clears all memories for a player by setting all wasDiscovered to false.
     */
    public void clearPlayerMemories(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));
            
            // Set all plants to not discovered
            Set<PlantInfo> clearedMemories = new HashSet<>();
            for (PlantInfo plant : memories) {
                clearedMemories.add(new PlantInfo(plant.getId(), plant.getPlantName(), false));
            }
            
            // Update in memory and save
            playerMemories.put(playerId, clearedMemories);
            getClaimedRewardTiers(playerId).clear();
            savePlayerMemories(playerId, clearedMemories);

            // Also forget the journal was opened, so the intro shows again
            playersWhoOpenedJournal.remove(playerId);
        }
    }

    /**
     * Unlocks all memories for a player by setting all wasDiscovered to true.
     */
    public void unlockAllPlayerMemories(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID playerId = playerRef.getUuid();
            Set<PlantInfo> memories = playerMemories.computeIfAbsent(playerId, k -> loadPlayerMemories(k));

            // Set all plants to discovered
            Set<PlantInfo> unlockedMemories = new HashSet<>();
            for (PlantInfo plant : memories) {
                unlockedMemories.add(new PlantInfo(plant.getId(), plant.getPlantName(), true));
            }

            // Update in memory and save
            playerMemories.put(playerId, unlockedMemories);
            savePlayerMemories(playerId, unlockedMemories);
        }
    }

    /**
     * Gets the complete list of plant IDs from plants.json.
     */
    public Set<String> getAllPlantIds() {
        return Collections.unmodifiableSet(allPlantsFromConfig.keySet());
    }
    
    /**
     * Gets the complete list of all plants from plants.json.
     */
    public Collection<PlantInfo> getAllPlantInfo() {
        return Collections.unmodifiableCollection(allPlantsFromConfig.values());
    }

    /**
     * Gets the plant categories from plants.json in declaration order.
     */
    public Map<String, List<String>> getPlantCategories() {
        return Collections.unmodifiableMap(plantCategories);
    }

    /**
     * Gets the plant ids of a category from plants.json.
     */
    public List<String> getPlantsInCategory(String category) {
        return plantCategories.getOrDefault(category, Collections.emptyList());
    }

    /**
     * Checks whether a player has discovered a specific plant.
     */
    public boolean isPlantDiscovered(PlayerRef playerRef, String plantId) {
        for (PlantInfo info : getPlayerMemories(playerRef)) {
            if (info.getId().equals(plantId)) {
                return info.wasDiscovered();
            }
        }
        return false;
    }

    /**
     * Gets the PlantInfo of a plant as recorded for a player, or null if the
     * plant has no memory entry for this player.
     */
    public PlantInfo getPlayerPlantInfo(PlayerRef playerRef, String plantId) {
        for (PlantInfo info : getPlayerMemories(playerRef)) {
            if (info.getId().equals(plantId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Gets the icon texture path of a plant item, or null if unavailable.
     */
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
    
    /**
     * Gets PlantInfo for a specific plant ID from plants.json.
     */
    public PlantInfo getPlantInfo(String plantId) {
        return allPlantsFromConfig.get(plantId);
    }
    
    /**
     * Gets the plant name as a translatable message for a specific plant ID.
     * The client resolves the translation in the player's current language.
     * Falls back to the raw plant ID if no translation is available.
     */
    public Message getPlantNameMessage(String plantId) {
        String translationKey = getPlantTranslationKey(plantId);
        if (translationKey != null) {
            return Message.translation(translationKey);
        }
        return Message.raw(plantId);
    }

    /**
     * Gets the translation key (e.g., "server.items.Plant_Flower_Bushy_Blue.name")
     * for a specific plant ID from the Hytale API.
     *
     * @param plantId The plant ID (e.g., "Plant_Flower_Bushy_Blue")
     * @return The translation key, or null if not found
     */
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

    /**
     * Gets the storage directory.
     */
    public Path getDataDirectory() {
        return dataDirectory;
    }
}
