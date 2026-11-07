package com.ratnaraj.herbarium.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ratnaraj.herbarium.Herbarium;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Persistence of player progression: one JSON file per player, holding the
 * journalOpened flag, the collected reward tiers and the plant memories.
 * Only knows how to read and write files; the in-memory state of players
 * lives in PlayerStore.
 */
public class PlayerFileStore {

    private final PlantCatalog catalog;

    private Path dataDirectory;

    public PlayerFileStore(PlantCatalog catalog) {
        this.catalog = catalog;
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

    public boolean isInitialized() {
        return dataDirectory != null && Files.exists(dataDirectory);
    }

    /**
     * Loads every valid save file, skipping unreadable ones with a warning.
     */
    public Map<UUID, PlayerData> loadAll() {
        Map<UUID, PlayerData> loaded = new HashMap<>();

        if (!isInitialized()) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Data directory not initialized, loading cancelled."
            );
            return loaded;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory, "*.json")) {
            for (Path file : stream) {
                try {
                    String playerId = file.getFileName().toString().replace(".json", "");
                    UUID uuid = UUID.fromString(playerId);
                    PlayerData data = loadPlayer(uuid);
                    if (!data.getPlants().isEmpty()) {
                        loaded.put(uuid, data);
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

        return loaded;
    }

    /**
     * Loads a player's progression from their file: the journalOpened
     * flag, collected reward tiers and plant memories. Missing or
     * corrupted files give a fresh progression.
     */
    public PlayerData loadPlayer(UUID playerId) {
        Path file = getPlayerFile(playerId);

        if (!Files.exists(file)) {
            return newPlayerData();
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }

            String fileContent = content.toString().trim();
            if (fileContent.isEmpty()) {
                return newPlayerData();
            }

            JsonObject rootObject = JsonParser.parseString(fileContent).getAsJsonObject();

            boolean journalOpened = rootObject.has("journalOpened")
                    && rootObject.get("journalOpened").getAsBoolean();

            Set<PlantInfo> plants = new HashSet<>();
            for (JsonElement element : rootObject.getAsJsonArray("plants")) {
                plants.add(parsePlantMemory(element));
            }

            Map<Integer, String> tiers = new ConcurrentHashMap<>();
            if (rootObject.has("collectedRewardTiers")) {
                for (JsonElement element : rootObject.getAsJsonArray("collectedRewardTiers")) {
                    try {
                        JsonObject entry = element.getAsJsonObject();
                        String collectedDate = entry.has("collectedDate")
                                && !entry.get("collectedDate").isJsonNull()
                                ? entry.get("collectedDate").getAsString() : "";
                        tiers.put(entry.get("tier").getAsInt(), collectedDate);
                    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
                        Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                                "[Herbarium] Invalid collected reward tier for " + playerId + ": " + element
                        );
                    }
                }
            }

            // Drop memories of plants the journal no longer tracks
            plants.removeIf(plant -> !catalog.getAllPlants().containsKey(plant.getId()));

            return new PlayerData(plants, tiers, journalOpened);
        } catch (IOException e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Error reading file for " + playerId + ": " + e.getMessage()
            );
            return newPlayerData();
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Corrupted memory file for " + playerId + ", reinitializing"
            );
            return newPlayerData();
        }
    }

    public void save(UUID playerId, PlayerData data) {
        if (dataDirectory == null) {
            return;
        }

        Path file = getPlayerFile(playerId);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            JsonObject root = new JsonObject();
            root.addProperty("journalOpened", data.hasOpenedJournal());

            Map<Integer, String> collected = new TreeMap<>(data.getCollectedRewardTiers());
            JsonArray collectedArray = new JsonArray();
            for (Map.Entry<Integer, String> entry : collected.entrySet()) {
                JsonObject collectedEntry = new JsonObject();
                collectedEntry.addProperty("tier", entry.getKey());
                collectedEntry.addProperty("collectedDate", entry.getValue());
                collectedArray.add(collectedEntry);
            }
            root.add("collectedRewardTiers", collectedArray);

            JsonArray jsonArray = new JsonArray();
            for (PlantInfo plant : data.getPlants()) {
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

    /** A fresh progression: every plant of the catalog, undiscovered. */
    private PlayerData newPlayerData() {
        Set<PlantInfo> plants = new HashSet<>();
        for (PlantInfo plant : catalog.getAllPlants().values()) {
            plants.add(new PlantInfo(plant.getId(), "", false));
        }
        return new PlayerData(plants, new ConcurrentHashMap<>(), false);
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
}
