package com.ratnaraj.herbarium.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantAssets;
import com.ratnaraj.herbarium.data.PlantInfo;
import com.ratnaraj.herbarium.data.PlayerStore;

import java.util.List;

public class PlantDetailsPanel {

    private static final int LOCATION_LABEL_COUNT = 4;
    private static final String LOCATIONS_FOUND_IN_KEY = "server.herbarium.location.found_in";
    private static final String LOCATION_UNKNOWN_KEY = "server.herbarium.location.unknown";

    public static void buildDetails(PlayerRef playerRef, UICommandBuilder commands,
                                    String selectedPlant) {
        PlayerStore store = Herbarium.getPlayerStore();

        if (selectedPlant == null) {
            commands.set("#PlantName.Text", "");
            setLocationsPanel(commands, null);
            commands.set("#PlantDiscovery.Text", "");
            commands.setNull("#PlantIcon.AssetPath");
            return;
        }

        setPlantName(commands, "#PlantName.Text", selectedPlant);

        setLocationsPanel(commands, Herbarium.getCatalog().getPlantLocations(selectedPlant));

        PlantInfo plantInfo = store.getPlayerPlantInfo(playerRef, selectedPlant);
        if (plantInfo != null && plantInfo.wasDiscovered()) {
            String date = plantInfo.getDiscoveredDate();
            String place = plantInfo.getDiscoveredPlace();
            String text = "";
            if (date != null) {
                text = "Discovered on " + formatDate(date);
                if (place != null) {
                    text = text + " at (" + place + ")";
                }
            }
            commands.set("#PlantDiscovery.Text", text);
        } else {
            commands.set("#PlantDiscovery.Text", "");
        }

        String icon = PlantAssets.getPlantIcon(selectedPlant);
        if (icon != null) {
            commands.set("#PlantIcon.AssetPath", icon);
        } else {
            commands.setNull("#PlantIcon.AssetPath");
        }
    }

    public static void setLocationsPanel(UICommandBuilder commands, List<String> locationKeys) {
        boolean hasPlant = locationKeys != null;
        boolean hasLocations = hasPlant && !locationKeys.isEmpty();

        commands.set("#PlantLocationsTitle.Visible", hasPlant);
        if (hasPlant) {
            commands.set("#PlantLocationsTitle.Text", Message.translation(LOCATIONS_FOUND_IN_KEY));
        } else {
            commands.set("#PlantLocationsTitle.Text", "");
        }

        commands.set("#PlantLocationUnknown.Visible", hasPlant && !hasLocations);
        if (hasPlant && !hasLocations) {
            commands.set("#PlantLocationUnknown.Text", Message.translation(LOCATION_UNKNOWN_KEY));
        } else {
            commands.set("#PlantLocationUnknown.Text", "");
        }

        for (int i = 0; i < LOCATION_LABEL_COUNT; i++) {
            String label = "#PlantLocation" + i;
            if (hasLocations && i < locationKeys.size()) {
                commands.set(label + ".Text", Message.translation(locationKeys.get(i)));
                commands.set(label + ".Visible", true);
            } else {
                commands.set(label + ".Text", "");
                commands.set(label + ".Visible", false);
            }
        }
    }

    public static String formatDate(String isoInstant) {
        int separatorIndex = isoInstant.indexOf('T');
        return separatorIndex > 0 ? isoInstant.substring(0, separatorIndex) : isoInstant;
    }

    public static void setPlantName(UICommandBuilder commands, String selector, String plantId) {
        String translationKey = PlantAssets.getPlantTranslationKey(plantId);
        if (translationKey != null) {
            commands.set(selector, Message.translation(translationKey));
        } else {
            commands.set(selector, plantId);
        }
    }


}
