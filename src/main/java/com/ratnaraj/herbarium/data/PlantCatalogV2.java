package com.ratnaraj.herbarium.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ratnaraj.herbarium.Herbarium;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Level;

public final class PlantCatalogV2 {

    private final Map<String, PlantInfo> allPlantsFromConfig;
    private final Map<String, List<String>> plantCategories = new LinkedHashMap<>();
    private final Map<String, PlantLocationsV2> plantLocations = new HashMap<>();

    public PlantCatalogV2() {
        this.allPlantsFromConfig = loadPlantsFromConfig();
    }

    private Map<String, PlantInfo> loadPlantsFromConfig() {
        Map<String, PlantInfo> plants = new LinkedHashMap<>();
        try {
            InputStream is = getClass().getResourceAsStream("/plants-v2.json");

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

                        JsonObject locationsJson = item.getAsJsonObject("locations");

                        plantLocations.put(id, new PlantLocationsV2(
                                readStringList(locationsJson, "regions"),
                                readStringList(locationsJson, "zones"),
                                readStringList(locationsJson, "portals")
                        ));
                    }

                    if (!categoryPlants.isEmpty()) {
                        plantCategories.put(category, Collections.unmodifiableList(categoryPlants));
                    }
                }

                Herbarium.getInstance().getLogger().at(Level.INFO).log(
                        "[Herbarium] Loaded " + plants.size() + " obtainable plants from plants-v2.json"
                );
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Failed to load plants-v2.json: " + e.getMessage()
            );
        }
        return Collections.unmodifiableMap(plants);
    }

    public Map<String, PlantInfo> getAllPlants() {
        return this.allPlantsFromConfig;
    }

    public PlantLocationsV2 getPlantLocations(String plantId) {
        return plantLocations.getOrDefault(plantId, PlantLocationsV2.EMPTY);
    }

    private static List<String> readStringList(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : parent.getAsJsonArray(key)) {
            values.add(value.getAsString());
        }
        return values;
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

    public Set<String> getCategoryNames() {
        return plantCategories.keySet();
    }

    public String getCategoryName(String inputCategory) {
        Set<String> categories = getCategoryNames();
        String categoryName = null;

        for (String category : categories) {
            if (category.equalsIgnoreCase(inputCategory)) {
                categoryName = category;
                break;
            }
        }

        return categoryName;
    }

    public boolean isPlantItem(String itemId) {
        if (itemId == null) return false;

        return itemId.startsWith("Plant_");
    }

}
