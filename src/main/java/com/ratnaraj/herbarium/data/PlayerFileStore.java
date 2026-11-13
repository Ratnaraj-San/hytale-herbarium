package com.ratnaraj.herbarium.data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ratnaraj.herbarium.Herbarium;

/**
 * Persistence of player progression: one JSON file per player, holding the journalOpened flag, the
 * collected reward tiers and the plant memories. Only knows how to read and write files; the
 * in-memory state of players lives in PlayerStore.
 */
public class PlayerFileStore {

    private static final String V1_BACKUP_PREFIX = "v1.";

    private final PlantCatalogV2 catalog;

    private Path dataDirectory;

    public PlayerFileStore(PlantCatalogV2 catalog) {
        this.catalog = catalog;
    }

    public void initialize(Path pluginDataDir) {
        this.dataDirectory = pluginDataDir;

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.SEVERE)
                    .log("[Herbarium] Error creating data directory: " + e.getMessage());
        }
    }

    public boolean isInitialized() {
        return dataDirectory != null && Files.exists(dataDirectory);
    }

    /** Loads every valid save file, skipping unreadable ones with a warning. */
    public Map<UUID, PlayerData> loadAll() {
        Map<UUID, PlayerData> loaded = new HashMap<>();

        if (!isInitialized()) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log("[Herbarium] Data directory not initialized, loading cancelled.");
            return loaded;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory, "*.json")) {
            for (Path file : stream) {
                try {
                    String fileName = file.getFileName().toString();
                    if (fileName.startsWith(V1_BACKUP_PREFIX)) {
                        continue;
                    }
                    String playerId = fileName.replace(".json", "");
                    UUID uuid = UUID.fromString(playerId);
                    PlayerData data = loadPlayer(uuid);
                    if (!data.getPlants().isEmpty()) {
                        loaded.put(uuid, data);
                    }
                } catch (IllegalArgumentException e) {
                    Herbarium.getInstance()
                            .getLogger()
                            .at(Level.WARNING)
                            .log("[Herbarium] Invalid memory file: " + file.getFileName());
                } catch (Exception e) {
                    Herbarium.getInstance()
                            .getLogger()
                            .at(Level.WARNING)
                            .log(
                                    "[Herbarium] Error loading "
                                            + file.getFileName()
                                            + ": "
                                            + e.getMessage());
                }
            }
        } catch (IOException e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.SEVERE)
                    .log("[Herbarium] Error loading memories: " + e.getMessage());
        }

        return loaded;
    }

    /**
     * Loads a player's progression from their file: the journalOpened flag, collected reward tiers
     * and plant memories. Missing or corrupted files give a fresh progression.
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

            if (!rootObject.has("pluginVersion")) {
                backupAsV1(file, playerId);
            }

            boolean journalOpened =
                    rootObject.has("journalOpened")
                            && rootObject.get("journalOpened").getAsBoolean();

            Set<PlantInfo> plants = new HashSet<>();
            for (JsonElement element : rootObject.getAsJsonArray("plants")) {
                plants.add(parsePlantMemory(element));
            }

            Map<Integer, PlayerData.CollectedTier> tiers = new ConcurrentHashMap<>();
            if (rootObject.has("collectedRewardTiers")) {
                for (JsonElement element : rootObject.getAsJsonArray("collectedRewardTiers")) {
                    try {
                        JsonObject entry = element.getAsJsonObject();
                        String collectedDate =
                                entry.has("collectedDate")
                                                && !entry.get("collectedDate").isJsonNull()
                                        ? entry.get("collectedDate").getAsString()
                                        : "";
                        String tierRewardsVersion =
                                entry.has("rewardsVersion")
                                                && entry.get("rewardsVersion").isJsonPrimitive()
                                        ? entry.get("rewardsVersion").getAsString()
                                        : "1.0.0";
                        tiers.put(
                                entry.get("tier").getAsInt(),
                                new PlayerData.CollectedTier(collectedDate, tierRewardsVersion));
                    } catch (ClassCastException | IllegalStateException | NullPointerException e) {
                        Herbarium.getInstance()
                                .getLogger()
                                .at(Level.WARNING)
                                .log(
                                        "[Herbarium] Invalid collected reward tier for "
                                                + playerId
                                                + ": "
                                                + element);
                    }
                }
            }

            Map<Integer, Set<String>> claimedCompensations = new ConcurrentHashMap<>();
            if (rootObject.has("claimedCompensations")
                    && rootObject.get("claimedCompensations").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry :
                        rootObject.getAsJsonObject("claimedCompensations").entrySet()) {
                    try {
                        Set<String> versions = new HashSet<>();
                        for (JsonElement version : entry.getValue().getAsJsonArray()) {
                            versions.add(version.getAsString());
                        }
                        claimedCompensations.put(Integer.parseInt(entry.getKey()), versions);
                    } catch (ClassCastException
                            | IllegalStateException
                            | NullPointerException
                            | NumberFormatException e) {
                        Herbarium.getInstance()
                                .getLogger()
                                .at(Level.WARNING)
                                .log(
                                        "[Herbarium] Invalid claimed compensation for "
                                                + playerId
                                                + ": "
                                                + entry);
                    }
                }
            }

            // The save must mirror the catalog exactly: keep discovered
            // state/date/place of known plants, drop stale ones, and add
            // catalog plants missing from the file as undiscovered
            Map<String, PlantInfo> memoriesFromFile = new HashMap<>();
            for (PlantInfo plant : plants) {
                memoriesFromFile.put(plant.getId(), plant);
            }
            Set<PlantInfo> merged = new HashSet<>();
            for (PlantInfo catalogPlant : catalog.getAllPlants().values()) {
                PlantInfo previous = memoriesFromFile.get(catalogPlant.getId());
                merged.add(
                        previous != null
                                ? previous
                                : new PlantInfo(catalogPlant.getId(), "", false));
            }

            return new PlayerData(merged, tiers, claimedCompensations, journalOpened);
        } catch (IOException e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log("[Herbarium] Error reading file for " + playerId + ": " + e.getMessage());
            return newPlayerData();
        } catch (Exception e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log("[Herbarium] Corrupted memory file for " + playerId + ", reinitializing");
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
            root.addProperty("pluginVersion", Herbarium.getPluginVersion());
            root.addProperty("journalOpened", data.hasOpenedJournal());

            Map<Integer, PlayerData.CollectedTier> collected =
                    new TreeMap<>(data.getCollectedRewardTiers());
            JsonArray collectedArray = new JsonArray();
            for (Map.Entry<Integer, PlayerData.CollectedTier> entry : collected.entrySet()) {
                JsonObject collectedEntry = new JsonObject();
                collectedEntry.addProperty("tier", entry.getKey());
                collectedEntry.addProperty("collectedDate", entry.getValue().collectedDate());
                collectedEntry.addProperty("rewardsVersion", entry.getValue().rewardsVersion());
                collectedArray.add(collectedEntry);
            }
            root.add("collectedRewardTiers", collectedArray);

            JsonObject claimedCompensationsJson = new JsonObject();
            Map<Integer, Set<String>> claimedSorted = new TreeMap<>(data.getClaimedCompensations());
            for (Map.Entry<Integer, Set<String>> entry : claimedSorted.entrySet()) {
                JsonArray versionsArray = new JsonArray();
                entry.getValue().stream()
                        .sorted(HerbariumRewards::compareVersions)
                        .forEach(versionsArray::add);
                claimedCompensationsJson.add(String.valueOf(entry.getKey()), versionsArray);
            }
            root.add("claimedCompensations", claimedCompensationsJson);

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
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.SEVERE)
                    .log(
                            "[Herbarium] Error saving memories for "
                                    + playerId
                                    + ": "
                                    + e.getMessage());
        }
    }

    private Path getPlayerFile(UUID playerId) {
        return dataDirectory.resolve(playerId.toString() + ".json");
    }

    /**
     * A save file without pluginVersion predates versioned saves: keep an untouched copy as
     * v1.<uuid>.json so the player can roll back by deleting the new file and renaming the backup.
     * The current version is stamped on the file at the next save.
     */
    private void backupAsV1(Path file, UUID playerId) {
        try {
            Path backup = dataDirectory.resolve(V1_BACKUP_PREFIX + file.getFileName());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.INFO)
                    .log(
                            "[Herbarium] Save of "
                                    + playerId
                                    + " had no pluginVersion, backed up as "
                                    + backup.getFileName());
        } catch (IOException e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log(
                            "[Herbarium] Could not create v1 backup for "
                                    + playerId
                                    + ": "
                                    + e.getMessage());
        }
    }

    /** A fresh progression: every plant of the catalog, undiscovered. */
    private PlayerData newPlayerData() {
        Set<PlantInfo> plants = new HashSet<>();
        for (PlantInfo plant : catalog.getAllPlants().values()) {
            plants.add(new PlantInfo(plant.getId(), "", false));
        }
        return new PlayerData(plants, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), false);
    }

    private PlantInfo parsePlantMemory(JsonElement element) {
        JsonObject jsonObject = element.getAsJsonObject();
        String id = jsonObject.get("id").getAsString();
        boolean wasDiscovered = jsonObject.get("wasDiscovered").getAsBoolean();
        String discoveredDate =
                jsonObject.has("discoveredDate") && !jsonObject.get("discoveredDate").isJsonNull()
                        ? jsonObject.get("discoveredDate").getAsString()
                        : null;
        String discoveredPlace =
                jsonObject.has("discoveredPlace") && !jsonObject.get("discoveredPlace").isJsonNull()
                        ? jsonObject.get("discoveredPlace").getAsString()
                        : null;
        return new PlantInfo(id, "", wasDiscovered, discoveredDate, discoveredPlace);
    }
}
