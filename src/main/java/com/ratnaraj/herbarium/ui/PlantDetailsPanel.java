package com.ratnaraj.herbarium.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantAssets;
import com.ratnaraj.herbarium.data.PlantCrafting;
import com.ratnaraj.herbarium.data.PlantInfo;
import com.ratnaraj.herbarium.data.PlantInteractions;
import com.ratnaraj.herbarium.data.PlantLocationsV2;
import com.ratnaraj.herbarium.data.PlantSource;
import com.ratnaraj.herbarium.data.PlayerStore;

public class PlantDetailsPanel {

    /** Same event as the LeftPanel tiles: opens that plant's page. */
    public static final String TYPE_SELECT_PLANT = "SelectPlant";

    private static final String LOCATION_ENTRY_UI = "Pages/Herbarium/components/LocationEntry.ui";
    private static final String ZONE_SECTION_UI = "Pages/Herbarium/components/ZoneSection.ui";
    private static final String INGREDIENT_TILE_UI = "Pages/Herbarium/components/IngredientTile.ui";
    private static final String PLANT_INGREDIENT_TILE_UI =
            "Pages/Herbarium/components/PlantIngredientTile.ui";
    private static final String PLANT_SOURCE_TILE_UI =
            "Pages/Herbarium/components/PlantSourceTile.ui";
    private static final String ITEM_SOURCE_TILE_UI =
            "Pages/Herbarium/components/ItemSourceTile.ui";
    private static final String LOCATIONS_FOUND_IN_KEY =
            "server.herbarium.ui.container.locations.title";
    private static final String LOCATION_UNKNOWN_KEY = "server.herbarium.ui.locations.unknown";
    private static final String PORTALS_TITLE_KEY = "server.herbarium.ui.locations.portals";
    private static final String DISCOVERED_ON_KEY = "server.herbarium.ui.plant.discoveredOn";
    private static final String DISCOVERED_AT_KEY = "server.herbarium.ui.plant.discoveredAt";
    private static final String UNLOCKED_BY_COMMAND_KEY =
            "server.herbarium.ui.plant.unlockedByCommand";
    private static final String COPY_ID_TOOLTIP_KEY = "server.herbarium.ui.plant.copy_id_tooltip";
    private static final String OBTAINED_FROM_TITLE_KEY =
            "server.herbarium.ui.container.obtained_from.title";
    private static final String GIVES_TITLE_KEY = "server.herbarium.ui.container.gives.title";
    private static final String INTERACTIONS_TITLE_KEY =
            "server.herbarium.ui.obtained_from.interactions";
    private static final String INTERACTION_BREAK_KEY = "server.herbarium.ui.interaction.break";
    private static final String INTERACTION_HARVEST_KEY = "server.herbarium.ui.interaction.harvest";
    private static final String INTERACTION_SHEARS_KEY = "server.herbarium.ui.interaction.shears";
    private static final String CRAFTING_TITLE_KEY = "server.herbarium.ui.obtained_from.crafting";
    private static final String PLANTS_TITLE_KEY = "server.herbarium.ui.obtained_from.plants";
    private static final String INTERACTION_SALVAGE_KEY = "server.herbarium.ui.interaction.salvage";
    private static final String INTERACTION_GROWTH_KEY = "server.herbarium.ui.interaction.growth";

    public static void buildDetails(
            PlayerRef playerRef,
            UICommandBuilder commands,
            UIEventBuilder events,
            String selectedPlant,
            boolean devView) {
        PlayerStore store = Herbarium.getPlayerStore();
        Set<String> discovered = getDiscoveredPlantIds(playerRef, devView);

        if (selectedPlant == null) {
            commands.set("#PlantName.Text", "");
            setLocationsPanel(commands, null);
            setObtainedFrom(commands, events, null, discovered);
            setGives(commands, events, null, discovered);
            setPlantId(commands, null, false);
            commands.set("#SpacerAfterObtainedFrom.Visible", false);
            commands.set("#PlantDiscovery.Text", "");
            commands.setNull("#PlantIcon.AssetPath");
            return;
        }

        setPlantNameText(commands, "#PlantName.Text", selectedPlant);
        setPlantId(commands, selectedPlant, devView);

        setLocationsPanel(commands, Herbarium.getCatalog().getPlantLocations(selectedPlant));
        boolean showObtainedFrom = setObtainedFrom(commands, events, selectedPlant, discovered);
        boolean showGives = setGives(commands, events, selectedPlant, discovered);
        commands.set("#SpacerAfterObtainedFrom.Visible", showObtainedFrom && showGives);

        PlantInfo plantInfo = store.getPlayerPlantInfo(playerRef, selectedPlant);
        Message discoveryText = null;

        if (plantInfo != null && plantInfo.wasDiscovered()) {
            if (plantInfo.getDiscoveredDate() != null) {
                String date = plantInfo.getDiscoveredDate();
                String place = plantInfo.getDiscoveredPlace();

                if (place != null) {
                    discoveryText =
                            Message.translation(DISCOVERED_AT_KEY)
                                    .param("date", formatDate(date))
                                    .param("place", place);
                } else {
                    discoveryText =
                            Message.translation(DISCOVERED_ON_KEY).param("date", formatDate(date));
                }
            } else if (plantInfo.getUnlockedByCommandDate() != null) {
                discoveryText =
                        Message.translation(UNLOCKED_BY_COMMAND_KEY)
                                .param("date", formatDate(plantInfo.getUnlockedByCommandDate()));
            }
        }

        if (discoveryText != null) {
            commands.set("#PlantDiscovery.Text", discoveryText);
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
            commands.set(
                    "#PlantContainerLocations #ContainerTitle.Text",
                    Message.translation(LOCATIONS_FOUND_IN_KEY));
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

        setLocationGroup(
                commands,
                "#PlantLocationsPortals",
                PORTALS_TITLE_KEY,
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
            setLocationGroup(
                    commands,
                    entry + " #ZoneSection",
                    zoneKey,
                    PlantLocationsV2.getRegionsForZone(zoneKey));
            // Spacer only between two visible blocks: not after the last zone
            // unless portals or the unknown label follows.
            boolean last = i == visibleZoneKeys.size() - 1;
            commands.set(entry + " #ZoneSpacer.Visible", !last || portalsVisible || unknownVisible);
        }
    }

    private static void setLocationGroup(
            UICommandBuilder commands, String section, String titleKey, List<String> entries) {
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
            // First entry has no top spacer; the rest are spaced.
            commands.append(list, LOCATION_ENTRY_UI);
            commands.set(list + "[" + i + "] #TopSpacer.Visible", i > 0);
            commands.set(
                    list + "[" + i + "] #LocationText.Text", Message.translation(entries.get(i)));
        }
    }

    /**
     * Ids of the plants this player discovered (all plants in dev view), used to render
     * undiscovered plants as empty tiles in Obtained from / Gives, like in the LeftPanel.
     */
    private static Set<String> getDiscoveredPlantIds(PlayerRef playerRef, boolean devView) {
        if (devView) {
            return Herbarium.getCatalog().getAllPlants().keySet();
        }
        Set<String> discovered = new HashSet<>();
        for (PlantInfo info : Herbarium.getPlayerStore().getPlayerMemories(playerRef)) {
            if (info.wasDiscovered()) {
                discovered.add(info.getId());
            }
        }
        return discovered;
    }

    private static boolean setObtainedFrom(
            UICommandBuilder commands,
            UIEventBuilder events,
            String plantId,
            Set<String> discovered) {
        PlantInteractions interactions =
                plantId != null
                        ? Herbarium.getCatalog().getPlantInteractions(plantId)
                        : PlantInteractions.NONE;
        PlantCrafting crafting =
                plantId != null ? Herbarium.getCatalog().getPlantCrafting(plantId) : null;
        List<PlantSource> sources =
                plantId != null ? Herbarium.getCatalog().getPlantSources(plantId) : List.of();

        boolean showInteractions = plantId != null && interactions.any();
        boolean showCrafting =
                crafting != null && !crafting.benches().isEmpty() && !crafting.inputs().isEmpty();
        boolean showPlants = plantId != null && !sources.isEmpty();
        boolean showContainer = showInteractions || showCrafting || showPlants;

        commands.set("#PlantContainerObtainedFrom.Visible", showContainer);

        if (!showContainer) {
            return false;
        }

        commands.set(
                "#PlantContainerObtainedFrom #ContainerTitle.Text",
                Message.translation(OBTAINED_FROM_TITLE_KEY));

        setInteractionsSection(commands, showInteractions, interactions);
        setCraftingSection(commands, events, showCrafting, crafting, discovered);
        setPlantsSection(commands, events, showPlants, sources, discovered);

        // Spacers only between two visible sections
        commands.set(
                "#SpacerAfterInteractions.Visible",
                showInteractions && (showCrafting || showPlants));
        commands.set("#SpacerAfterCrafting.Visible", showCrafting && showPlants);
        return true;
    }

    /**
     * Gives section: reverse of Obtained from / Plants — one PlantSourceTile per plant this plant
     * gives, with the interaction icons below (craft icon for craft relations). Returns whether the
     * container is visible.
     */
    private static boolean setGives(
            UICommandBuilder commands,
            UIEventBuilder events,
            String plantId,
            Set<String> discovered) {
        List<PlantSource> gives =
                plantId != null ? Herbarium.getCatalog().getPlantGives(plantId) : List.of();
        boolean visible = !gives.isEmpty();

        String container = "#PlantContainerGives";
        commands.set(container + ".Visible", visible);

        if (!visible) {
            commands.set(container + " #ContainerTitle.Text", "");
            return false;
        }

        commands.set(container + " #ContainerTitle.Text", Message.translation(GIVES_TITLE_KEY));

        String list = container + " #GivesList";
        commands.clear(list);

        for (int i = 0; i < gives.size(); i++) {
            PlantSource gave = gives.get(i);
            boolean isPlant = Herbarium.getCatalog().isPlantItem(gave.plantId());
            String entry =
                    appendEntry(
                            commands,
                            list,
                            isPlant ? PLANT_SOURCE_TILE_UI : ITEM_SOURCE_TILE_UI,
                            i);

            if (isPlant) {
                setPlantTileAppearance(
                        commands,
                        entry + " #PlantTile",
                        gave.plantId(),
                        discovered.contains(gave.plantId()));
                bindOpenPlantPage(events, entry, gave.plantId());
            } else {
                // Non-plant child item: ingredient tile, name only as tooltip.
                setAssetTooltip(commands, entry + " #IngredientTile", gave.plantId());
                setAssetIcon(
                        commands,
                        entry + " #IngredientTile #IngredientIcon",
                        iconOverride(gave.plantId()));
            }

            String icons = entry + " #SourceInteractions";
            commands.set(icons + " #IconBreak.Visible", gave.drop());
            commands.set(
                    icons + " #IconBreak.TooltipTextSpans",
                    Message.translation(INTERACTION_BREAK_KEY));
            commands.set(icons + " #IconHarvest.Visible", gave.collect());
            commands.set(
                    icons + " #IconHarvest.TooltipTextSpans",
                    Message.translation(INTERACTION_HARVEST_KEY));
            commands.set(icons + " #IconShears.Visible", gave.shears());
            commands.set(
                    icons + " #IconShears.TooltipTextSpans",
                    Message.translation(INTERACTION_SHEARS_KEY));
            commands.set(icons + " #IconSalvage.Visible", gave.salvage());
            commands.set(
                    icons + " #IconSalvage.TooltipTextSpans",
                    Message.translation(INTERACTION_SALVAGE_KEY));
            commands.set(icons + " #IconGrowth.Visible", gave.growth() && !gave.magical());
            commands.set(
                    icons + " #IconGrowth.TooltipTextSpans",
                    Message.translation(INTERACTION_GROWTH_KEY));
            commands.set(icons + " #IconGrowthMagical.Visible", gave.magical());
            commands.set(
                    icons + " #IconGrowthMagical.TooltipTextSpans",
                    Message.translation(INTERACTION_GROWTH_KEY));
            // Craft: bench icon on top of the CraftFarming fallback when the recipe's
            // bench is known; tooltip names the bench, else generic "Crafting".
            boolean hasBench = gave.craft() && gave.bench() != null && !gave.bench().isEmpty();
            commands.set(icons + " #IconCraft.Visible", gave.craft());
            commands.set(icons + " #CraftBenchIcon.Visible", hasBench);
            if (hasBench) {
                setAssetIcon(commands, icons + " #CraftBenchIcon", gave.bench());
                commands.set(icons + " #IconCraft.TooltipTextSpans", assetMessage(gave.bench()));
            } else {
                commands.set(
                        icons + " #IconCraft.TooltipTextSpans",
                        Message.translation(CRAFTING_TITLE_KEY));
            }
        }

        return true;
    }

    private static void setPlantsSection(
            UICommandBuilder commands,
            UIEventBuilder events,
            boolean visible,
            List<PlantSource> sources,
            Set<String> discovered) {
        String section = "#PlantContainerObtainedFrom #PlantContainerObtainedFromPlants";
        String list = beginSection(commands, section, visible, PLANTS_TITLE_KEY);

        if (list == null) {
            return;
        }

        for (int j = 0; j < sources.size(); j++) {
            PlantSource source = sources.get(j);
            String entry = appendEntry(commands, list, PLANT_SOURCE_TILE_UI, j);

            setPlantTileAppearance(
                    commands,
                    entry + " #PlantTile",
                    source.plantId(),
                    discovered.contains(source.plantId()));
            bindOpenPlantPage(events, entry, source.plantId());

            String icons = entry + " #SourceInteractions";
            commands.set(icons + " #IconBreak.Visible", source.drop());
            commands.set(
                    icons + " #IconBreak.TooltipTextSpans",
                    Message.translation(INTERACTION_BREAK_KEY));
            commands.set(icons + " #IconShears.Visible", source.shears());
            commands.set(
                    icons + " #IconShears.TooltipTextSpans",
                    Message.translation(INTERACTION_SHEARS_KEY));
            commands.set(icons + " #IconSalvage.Visible", source.salvage());
            commands.set(
                    icons + " #IconSalvage.TooltipTextSpans",
                    Message.translation(INTERACTION_SALVAGE_KEY));
            commands.set(icons + " #IconGrowth.Visible", source.growth() && !source.magical());
            commands.set(
                    icons + " #IconGrowth.TooltipTextSpans",
                    Message.translation(INTERACTION_GROWTH_KEY));
            commands.set(icons + " #IconGrowthMagical.Visible", source.magical());
            commands.set(
                    icons + " #IconGrowthMagical.TooltipTextSpans",
                    Message.translation(INTERACTION_GROWTH_KEY));
        }
    }

    private static void setInteractionsSection(
            UICommandBuilder commands, boolean visible, PlantInteractions interactions) {
        String section = "#PlantContainerObtainedFrom #PlantContainerObtainedFromInteractions";

        if (beginSection(commands, section, visible, INTERACTIONS_TITLE_KEY) == null) {
            return;
        }

        String row = section + " #InteractionsHeader #SourceInteractions";

        setInteractionIcon(
                commands, row + " #IconBreak", interactions.canBreak(), INTERACTION_BREAK_KEY);
        setInteractionIcon(
                commands,
                row + " #IconHarvest",
                interactions.canCollect(),
                INTERACTION_HARVEST_KEY);
        setInteractionIcon(
                commands, row + " #IconShears", interactions.canShear(), INTERACTION_SHEARS_KEY);
    }

    private static void setCraftingSection(
            UICommandBuilder commands,
            UIEventBuilder events,
            boolean visible,
            PlantCrafting crafting,
            Set<String> discovered) {
        String section = "#PlantContainerObtainedFrom #PlantContainerObtainedFromCrafting";
        String body = beginSection(commands, section, visible, CRAFTING_TITLE_KEY);

        if (body == null || crafting == null) {
            return;
        }

        // A recipe has a single bench requirement (verified in plants-v2.json).
        // Its icon sits in the section header (48px, right side); name only as tooltip.
        PlantCrafting.Bench bench = crafting.benches().get(0);
        String header = section + " #SectionHeader";
        commands.set(header + " #HeaderIcon.Visible", true);
        setAssetTooltip(commands, header + " #HeaderIcon", bench.itemId());
        setAssetIcon(commands, header + " #HeaderIcon", bench.itemId());

        for (int j = 0; j < crafting.inputs().size(); j++) {
            PlantCrafting.Ingredient ingredient = crafting.inputs().get(j);
            boolean isPlant = Herbarium.getCatalog().isPlantItem(ingredient.itemId());
            String entry =
                    appendEntry(
                            commands,
                            body,
                            isPlant ? PLANT_INGREDIENT_TILE_UI : INGREDIENT_TILE_UI,
                            j);

            if (isPlant) {
                // Plant ingredient: PlantTile shrunk to the ingredient tile size,
                // rendered like in the LeftPanel (empty tile when undiscovered).
                setPlantTileAppearance(
                        commands,
                        entry + " #PlantTile",
                        ingredient.itemId(),
                        discovered.contains(ingredient.itemId()));
                bindOpenPlantPage(events, entry, ingredient.itemId());
            } else {
                setAssetTooltip(commands, entry + " #IngredientTile", ingredient.itemId());
                setAssetIcon(
                        commands, entry + " #IngredientTile #IngredientIcon", ingredient.itemId());
            }

            commands.set(entry + " #IngredientAmount.Text", "x" + ingredient.quantity());
        }
    }

    /**
     * Common section setup: visibility, title, cleared list. Returns the list selector, or null
     * when the section is hidden.
     */
    private static String beginSection(
            UICommandBuilder commands, String section, boolean visible, String titleKey) {
        String list = section + " #SectionList";

        commands.set(section + ".Visible", visible);
        commands.set(section + " #SectionTitle.Visible", visible);
        commands.clear(list);

        if (!visible) {
            commands.set(section + " #SectionTitle.Text", "");
            return null;
        }

        commands.set(section + " #SectionTitle.Text", Message.translation(titleKey));
        return list;
    }

    private static String appendEntry(
            UICommandBuilder commands, String list, String uiFile, int index) {
        commands.append(list, uiFile);
        return list + "[" + index + "]";
    }

    /**
     * Crop-specific life essences (Ingredient_Life_Essence_<Crop>) all use the generic Essence of
     * Life icon; the tooltip still names the specific essence.
     */
    private static String iconOverride(String assetId) {
        if (assetId != null
                && assetId.startsWith("Ingredient_Life_Essence_")
                && !assetId.equals("Ingredient_Life_Essence_Concentrated")
                && !assetId.equals("Ingredient_Life_Essence_100")) {
            return "Ingredient_Life_Essence";
        }
        return assetId;
    }

    /**
     * Makes a plant tile clickable: clicking it opens that plant's page (same event as the
     * LeftPanel tiles). The plant id is embedded literally in the binding; bindings are re-sent
     * with every sendUpdate, so they stay correct when the list is rebuilt for another plant. (A
     * "#… #IdValue.TooltipText" reference is NOT resolved — the client sends the selector string
     * itself.)
     */
    private static void bindOpenPlantPage(UIEventBuilder events, String entry, String plantId) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                entry + " #PlantTile #ButtonNotSelected",
                EventData.of("Type", TYPE_SELECT_PLANT).append("Plant", plantId));
    }

    /**
     * Plant tile appearance, same as in the LeftPanel: discovered -> button with name tooltip +
     * icon; undiscovered -> empty background, no icon.
     */
    private static void setPlantTileAppearance(
            UICommandBuilder commands, String tile, String plantId, boolean isDiscovered) {
        commands.set(tile + " #ButtonNotSelected.Visible", isDiscovered);
        commands.set(tile + " #EmptyBackground.Visible", !isDiscovered);

        if (isDiscovered) {
            setPlantName(commands, tile + " #ButtonNotSelected.TooltipTextSpans", plantId);
            setAssetIcon(commands, tile + " #Icon", plantId);
        } else {
            commands.setNull(tile + " #Icon.AssetPath");
        }
    }

    private static Message assetMessage(String assetId) {
        String translationKey = PlantAssets.getPlantTranslationKey(assetId);
        return translationKey != null ? Message.translation(translationKey) : Message.raw(assetId);
    }

    /**
     * Label.Text is String-typed on the client: Message.translation is accepted, but Message.raw is
     * not ("No parameterless constructor for System.String"). Fall back to the raw id string when
     * the asset has no translation key.
     */
    private static void setAssetText(UICommandBuilder commands, String selector, String assetId) {
        String translationKey = PlantAssets.getPlantTranslationKey(assetId);
        if (translationKey != null) {
            commands.set(selector, Message.translation(translationKey));
        } else {
            commands.set(selector, assetId);
        }
    }

    private static void setAssetLabel(
            UICommandBuilder commands, String labelSelector, String assetId) {
        setAssetText(commands, labelSelector + ".Text", assetId);
    }

    private static void setAssetTooltip(
            UICommandBuilder commands, String selector, String assetId) {
        commands.set(selector + ".TooltipTextSpans", assetMessage(assetId));
    }

    private static void setAssetIcon(
            UICommandBuilder commands, String iconSelector, String assetId) {
        String icon = PlantAssets.getPlantIcon(assetId);

        if (icon != null) {
            commands.set(iconSelector + ".AssetPath", icon);
        } else {
            commands.setNull(iconSelector + ".AssetPath");
        }
    }

    private static void setInteractionIcon(
            UICommandBuilder commands, String icon, boolean visible, String tooltipKey) {
        commands.set(icon + ".Visible", visible);

        // TooltipTextSpans rejects null; clear with an empty message instead.
        commands.set(
                icon + ".TooltipTextSpans",
                visible ? Message.translation(tooltipKey) : Message.raw(""));
    }

    public static String formatDate(String isoInstant) {
        int separatorIndex = isoInstant.indexOf('T');
        return separatorIndex > 0 ? isoInstant.substring(0, separatorIndex) : isoInstant;
    }

    /** For Message-typed targets (e.g. TooltipTextSpans). */
    public static void setPlantName(UICommandBuilder commands, String selector, String plantId) {
        commands.set(selector, assetMessage(plantId));
    }

    /** For String-typed Label.Text targets. */
    public static void setPlantNameText(
            UICommandBuilder commands, String selector, String plantId) {
        setAssetText(commands, selector, plantId);
    }

    public static void setPlantId(UICommandBuilder commands, String plantId, boolean devView) {
        commands.set("#PlantIdWrap.Visible", devView);
        if (devView) {
            commands.set("#PlantId.Value", plantId);
            commands.set("#PlantIdWrap.TooltipTextSpans", Message.translation(COPY_ID_TOOLTIP_KEY));
        } else {
            commands.set("#PlantIdWrap.TooltipTextSpans", Message.raw(""));
        }
    }
}
