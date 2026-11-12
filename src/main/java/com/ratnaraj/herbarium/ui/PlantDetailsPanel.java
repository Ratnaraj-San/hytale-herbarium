package com.ratnaraj.herbarium.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantAssets;
import com.ratnaraj.herbarium.data.PlantInfo;
import com.ratnaraj.herbarium.data.PlantInteractions;
import com.ratnaraj.herbarium.data.PlantLocationsV2;
import com.ratnaraj.herbarium.data.PlayerStore;

import java.util.ArrayList;
import java.util.List;

public class PlantDetailsPanel {

    private static final String LOCATION_ENTRY_UI = "Pages/Herbarium/components/LocationEntry.ui";
    private static final String LOCATION_ENTRY_SPACED_UI = "Pages/Herbarium/components/LocationEntrySpaced.ui";
    private static final String ZONE_SECTION_UI = "Pages/Herbarium/components/ZoneSection.ui";
    private static final String LOCATIONS_FOUND_IN_KEY = "server.herbarium.ui.container.locations.title";
    private static final String LOCATION_UNKNOWN_KEY = "server.herbarium.ui.locations.unknown";
    private static final String PORTALS_TITLE_KEY = "server.herbarium.ui.locations.portals";
    private static final String DISCOVERED_ON_KEY = "server.herbarium.ui.plant.discoveredOn";
    private static final String DISCOVERED_AT_KEY = "server.herbarium.ui.plant.discoveredAt";
    private static final String OBTAINED_FROM_TITLE_KEY = "server.herbarium.ui.container.obtained_from.title";
    private static final String INTERACTIONS_TITLE_KEY = "server.herbarium.ui.obtained_from.interactions";
    private static final String INTERACTION_BREAK_KEY = "server.herbarium.ui.interaction.break";
    private static final String INTERACTION_COLLECT_KEY = "server.herbarium.ui.interaction.collect";
    private static final String INTERACTION_SHEARS_KEY = "server.herbarium.ui.interaction.shears";

    public static void buildDetails(
            PlayerRef playerRef,
            UICommandBuilder commands,
            String selectedPlant,
            boolean devView
    ) {
        PlayerStore store = Herbarium.getPlayerStore();

        if (selectedPlant == null) {
            commands.set("#PlantName.Text", "");
            setLocationsPanel(commands, null);
            setObtainedFrom(commands, null);
            commands.set("#PlantDiscovery.Text", "");
            commands.setNull("#PlantIcon.AssetPath");
            return;
        }

        setPlantName(commands, "#PlantName.Text", selectedPlant);
        setPlantId(commands, "#PlantId.Text", selectedPlant, devView);

        setLocationsPanel(commands, Herbarium.getCatalog().getPlantLocations(selectedPlant));
        setObtainedFrom(commands, selectedPlant);

        PlantInfo plantInfo = store.getPlayerPlantInfo(playerRef, selectedPlant);
        if (plantInfo != null && plantInfo.wasDiscovered() && plantInfo.getDiscoveredDate() != null) {
            String date = plantInfo.getDiscoveredDate();
            String place = plantInfo.getDiscoveredPlace();
            Message text;
            if (place != null) {
                text = Message.translation(DISCOVERED_AT_KEY)
                        .param("date", formatDate(date))
                        .param("place", place);
            } else {
                text = Message.translation(DISCOVERED_ON_KEY).param("date", formatDate(date));
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

    public static void setLocationsPanel(UICommandBuilder commands, PlantLocationsV2 locations) {
        boolean hasPlant = locations != null;
        boolean hasLocations = hasPlant && !locations.isEmpty();

        commands.set("#PlantContainerLocations.Visible", hasPlant);

        if (hasPlant) {
            commands.set("#PlantContainerLocations #ContainerTitle.Text", Message.translation(LOCATIONS_FOUND_IN_KEY));
        } else {
            commands.set("#PlantContainerLocations #ContainerTitle.Text", "");
        }

        commands.set("#PlantLocationUnknown.Visible", hasPlant && !hasLocations);

        if (hasPlant && !hasLocations) {
            commands.set("#PlantLocationUnknown.Text", Message.translation(LOCATION_UNKNOWN_KEY));
        } else {
            commands.set("#PlantLocationUnknown.Text", "");
        }

        boolean portalsVisible = hasPlant && !locations.getPortals().isEmpty();
        boolean unknownVisible = hasPlant && !hasLocations;

        setLocationGroup(commands, "#PlantLocationsPortals", PORTALS_TITLE_KEY,
                portalsVisible ? locations.getPortals() : List.of());

        // One appended ZoneSection per zone of the plant, listing that zone's regions (game data).
        List<String> plantZones = hasPlant ? locations.getZones() : List.of();
        List<String> visibleZoneKeys = new ArrayList<>();
        for (String zoneKey : PlantLocationsV2.REGIONS_BY_ZONE.keySet()) {
            if (plantZones.contains(zoneKey)) {
                visibleZoneKeys.add(zoneKey);
            }
        }

        String zonedList = "#ZonedRegions";
        commands.clear(zonedList);
        for (int i = 0; i < visibleZoneKeys.size(); i++) {
            String zoneKey = visibleZoneKeys.get(i);
            String entry = zonedList + "[" + i + "]";
            commands.append(zonedList, ZONE_SECTION_UI);
            setLocationGroup(commands, entry + " #ZoneSection", zoneKey,
                    PlantLocationsV2.getRegionsForZone(zoneKey));
            // Spacer only between two visible blocks: not after the last zone
            // unless portals or the unknown label follows.
            boolean last = i == visibleZoneKeys.size() - 1;
            commands.set(entry + " #ZoneSpacer.Visible", !last || portalsVisible || unknownVisible);
        }
    }

    private static void setLocationGroup(UICommandBuilder commands, String section,
                                         String titleKey, List<String> entries) {
        String title = section + " #SectionTitle";
        String list = section + " #SectionList";
        boolean visible = !entries.isEmpty();
        commands.set(section + ".Visible", visible);
        commands.set(title + ".Visible", visible);

        if (visible) {
            commands.set(title + ".Text", Message.translation(titleKey));
        } else {
            commands.set(title + ".Text", "");
        }

        commands.clear(list);

        for (int i = 0; i < entries.size(); i++) {
            // First entry has no top padding; the rest are spaced.
            commands.append(list, i == 0 ? LOCATION_ENTRY_UI : LOCATION_ENTRY_SPACED_UI);
            commands.set(list + "[" + i + "] #LocationText.Text", Message.translation(entries.get(i)));
        }
    }

    private static void setObtainedFrom(UICommandBuilder commands, String plantId) {
        PlantInteractions interactions = plantId != null
                ? Herbarium.getCatalog().getPlantInteractions(plantId)
                : PlantInteractions.NONE;
        boolean showSection = plantId != null && interactions.any();

        commands.set("#PlantContainerObtainedFrom.Visible", showSection);

        if (!showSection) { return; }

        commands.set("#PlantContainerObtainedFrom #ContainerTitle.Text", Message.translation(OBTAINED_FROM_TITLE_KEY));

        String section = "#PlantContainerObtainedFrom #PlantContainerObtainedFromInteractions";

        commands.set(section + ".Visible", true);
        commands.set(section + " #SectionTitle.Visible", true);
        commands.set(section + " #SectionTitle.Text", Message.translation(INTERACTIONS_TITLE_KEY));

        String row = section + " #InteractionsHeader #InteractionsRow";

        setInteractionIcon(
                commands,
                row + " #InteractionBreak",
                interactions.canBreak(),
                INTERACTION_BREAK_KEY
        );
        setInteractionIcon(
                commands,
                row + " #InteractionCollect",
                interactions.canCollect(),
                INTERACTION_COLLECT_KEY
        );
        setInteractionIcon(
                commands,
                row + " #InteractionShears",
                interactions.canShear(),
                INTERACTION_SHEARS_KEY
        );
    }

    private static void setInteractionIcon(UICommandBuilder commands, String icon, boolean visible, String tooltipKey) {
        commands.set(icon + ".Visible", visible);

        if (visible) {
            commands.set(icon + ".TooltipText", Message.translation(tooltipKey));
        } else {
            commands.set(icon + ".TooltipText", "");
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

    public static void setPlantId(UICommandBuilder commands, String selector, String plantId, boolean devView) {
        if (devView) { commands.set(selector, plantId); }
    }

}
