package com.ratnaraj.herbarium.data;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ratnaraj.herbarium.Herbarium;

public final class PlantCatalog {

    private final Map<String, PlantInfo> allPlantsFromConfig;
    private final Map<String, List<String>> plantCategories = new LinkedHashMap<>();
    private final Map<String, List<String>> plantLocations = new HashMap<>();

    public PlantCatalog() {
        this.allPlantsFromConfig = loadPlantsFromConfig();
    }

    private Map<String, PlantInfo> loadPlantsFromConfig() {
        Map<String, PlantInfo> plants = new LinkedHashMap<>();
        try {
            InputStream is = getClass().getResourceAsStream("/plants.json");
            if (is != null) {
                JsonObject config =
                        JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                for (String category : config.keySet()) {
                    JsonArray items = config.getAsJsonArray(category);
                    List<String> categoryPlants = new ArrayList<>();
                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        String id = item.get("id").getAsString();

                        boolean obtainable =
                                !item.has("obtainableInExplorationMode")
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
                Herbarium.getInstance()
                        .getLogger()
                        .at(Level.INFO)
                        .log(
                                "[Herbarium] Loaded "
                                        + plants.size()
                                        + " obtainable plants from plants.json");
            }
        } catch (Exception e) {
            Herbarium.getInstance()
                    .getLogger()
                    .at(Level.WARNING)
                    .log("[Herbarium] Failed to load plants.json: " + e.getMessage());
        }
        return Collections.unmodifiableMap(plants);
    }

    public Map<String, PlantInfo> getAllPlants() {
        return this.allPlantsFromConfig;
    }

    public List<String> getPlantLocations(String plantId) {
        return plantLocations.getOrDefault(plantId, Collections.emptyList());
    }

    public Map<String, List<String>> getPlantCategories() {
        return Collections.unmodifiableMap(plantCategories);
    }

    public List<String> getPlantsInCategory(String category) {
        return plantCategories.getOrDefault(category, Collections.emptyList());
    }

    public int getTotalPlantCount() {
        return allPlantsFromConfig.size();
    }

    public boolean isPlantItem(String itemId) {
        if (itemId == null) {
            return false;
        }

        return itemId.startsWith("Plant_");
    }
}
