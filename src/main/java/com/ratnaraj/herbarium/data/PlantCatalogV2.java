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
    private final Map<String, PlantInteractions> plantInteractions = new HashMap<>();
    private final Map<String, PlantCrafting> plantCrafting = new HashMap<>();
    private final Map<String, List<PlantSource>> plantSources = new HashMap<>();

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

                        JsonObject obtainedFrom = item.getAsJsonObject("obtainedFrom");

                        plantInteractions.put(id, readInteractions(obtainedFrom));

                        if (obtainedFrom != null
                                && obtainedFrom.has("crafting")
                                && !obtainedFrom.get("crafting").isJsonNull()
                        ) {
                            plantCrafting.put(id, readCrafting(obtainedFrom.getAsJsonObject("crafting")));
                        }

                        plantSources.put(id, readSources(obtainedFrom));
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

    public PlantInteractions getPlantInteractions(String plantId) {
        return plantInteractions.getOrDefault(plantId, PlantInteractions.NONE);
    }

    public PlantCrafting getPlantCrafting(String plantId) {
        return plantCrafting.get(plantId);
    }

    public List<PlantSource> getPlantSources(String plantId) {
        return plantSources.getOrDefault(plantId, Collections.emptyList());
    }

    private static List<PlantSource> readSources(JsonObject obtainedFrom) {
        List<PlantSource> sources = new ArrayList<>();

        if (obtainedFrom != null && obtainedFrom.has("plants") && obtainedFrom.get("plants").isJsonArray()) {
            for (JsonElement e : obtainedFrom.getAsJsonArray("plants")) {
                JsonObject s = e.getAsJsonObject();
                sources.add(new PlantSource(
                        s.has("id") ? s.get("id").getAsString() : "",
                        readBool(s, "drop"),
                        readBool(s, "shears"),
                        readBool(s, "salvage"),
                        readBool(s, "growth"),
                        readBool(s, "magical")
                ));
            }
        }

        return List.copyOf(sources);
    }

    private static PlantCrafting readCrafting(JsonObject crafting) {
        List<PlantCrafting.Ingredient> inputs = new ArrayList<>();

        if (crafting.has("Input") && crafting.get("Input").isJsonArray()) {
            for (JsonElement e : crafting.getAsJsonArray("Input")) {
                JsonObject in = e.getAsJsonObject();
                inputs.add(new PlantCrafting.Ingredient(
                        in.has("ItemId") ? in.get("ItemId").getAsString() : "",
                        in.has("Quantity") && !in.get("Quantity").isJsonNull() ? in.get("Quantity").getAsInt() : 1
                ));
            }
        }

        List<PlantCrafting.Bench> benches = new ArrayList<>();

        if (crafting.has("BenchRequirement") && crafting.get("BenchRequirement").isJsonArray()) {
            for (JsonElement e : crafting.getAsJsonArray("BenchRequirement")) {
                JsonObject b = e.getAsJsonObject();
                String benchId = b.has("Id") ? b.get("Id").getAsString() : "";
                String benchItemId = b.has("BenchItemId") && !b.get("BenchItemId").isJsonNull()
                        ? b.get("BenchItemId").getAsString() : benchId;
                benches.add(new PlantCrafting.Bench(
                        benchId,
                        benchItemId,
                        readStringList(b, "Categories"),
                        b.has("RequiredTierLevel") && !b.get("RequiredTierLevel").isJsonNull()
                                ? b.get("RequiredTierLevel").getAsInt() : 0
                ));
            }
        }

        int outputQuantity = crafting.has("OutputQuantity") && !crafting.get("OutputQuantity").isJsonNull()
                ? crafting.get("OutputQuantity").getAsInt() : 1;

        return new PlantCrafting(List.copyOf(inputs), List.copyOf(benches), outputQuantity);
    }

    private static PlantInteractions readInteractions(JsonObject obtainedFrom) {
        if (obtainedFrom == null || !obtainedFrom.has("interactions")) {
            return PlantInteractions.NONE;
        }

        JsonObject interactions = obtainedFrom.getAsJsonObject("interactions");

        return new PlantInteractions(
                readBool(interactions, "break"),
                readBool(interactions, "collect"),
                readBool(interactions, "shears")
        );
    }

    private static boolean readBool(JsonObject parent, String key) {
        return parent != null && parent.has(key) && !parent.get(key).isJsonNull()
                && parent.get(key).getAsBoolean();
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
