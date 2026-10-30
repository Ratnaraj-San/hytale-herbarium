package com.ratnaraj.herbarium;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Botanical journal shown by /herbarium ui.
 * Mirrors the vanilla Memories page flow: a category overview panel and a
 * per-category plant grid with a details panel, swapped through rebuild().
 */
public class HerbariumUIPage extends InteractiveCustomUIPage<HerbariumUIPage.PageEventData> {

    private static final String TYPE_SELECT_CATEGORY = "SelectCategory";
    private static final String TYPE_SELECT_PLANT = "SelectPlant";
    private static final String TYPE_BACK = "Back";
    private static final String TYPE_COLLECT_REWARD = "CollectReward";

    /**
     * Translation key shared with the Herbarium Journal item name, so the
     * on-screen title always matches the in-hand item name.
     */
    private static final String JOURNAL_NAME_KEY = "server.items.Herbarium_Journal.name";

    /**
     * Number of location labels available in PlantPanel.ui. Text properties
     * accept a single translation message only, so each vanilla location key
     * of plants.json is rendered in its own label.
     */
    private static final int LOCATION_LABEL_COUNT = 4;

    /**
     * Translation keys for the plant locations panel header and the
     * "location unknown" fallback. Set at runtime: .ui documents cannot
     * reference plugin translation keys with %refs, the client fails to load
     * the document when the key is not resolvable at parse time.
     */
    private static final String LOCATIONS_FOUND_IN_KEY = "server.herbarium.location.found_in";
    private static final String LOCATION_UNKNOWN_KEY = "server.herbarium.location.unknown";

    /**
     * Width used to place the chest markers along the progress bar, in the
     * same 0-1000 coordinates as the vanilla Memories page.
     */
    private static final int CHEST_MARKER_SCALE = 1000;

    /**
     * Translation keys of the chest marker tooltips. Set at runtime with
     * Message.translation.
     */
    private static final String CHEST_LOCKED_TOOLTIP_KEY = "server.herbarium.rewards.chestLocked.tooltipText";
    private static final String CHEST_ACTIVE_TOOLTIP_KEY = "server.herbarium.rewards.chestActive.tooltipText";
    private static final String CHEST_COLLECTED_TOOLTIP_KEY = "server.herbarium.rewards.chestCollected.tooltipText";

    /**
     * Translation keys of the rewards button.
     */
    private static final String COLLECT_BUTTON_KEY = "server.herbarium.rewards.collectButton";
    private static final String ALL_COLLECTED_KEY = "server.herbarium.rewards.allCollected";

    /**
     * Vanilla sound event played when a reward is successfully collected.
     */
    private static final String COLLECT_SOUND_EVENT = "SFX_MemoryRestored";

    /**
     * Colors of the reward list shown in the chest tooltips.
     */
    private static final Color REWARD_QUANTITY_COLOR = new Color(0x88, 0x88, 0x88);
    private static final Color REWARD_ITEM_COLOR = Color.WHITE;
    private static final Color REWARD_COLLECTED_COLOR = new Color(0x88, 0x88, 0x88);

    /**
     * Icon shown for each category tile, keyed by plants.json category.
     * All values are vanilla item icon assets. Categories without an entry
     * fall back to the icon of their first known plant item.
     */
    private static final Map<String, String> CATEGORY_ICON_OVERRIDES = Map.ofEntries(
            Map.entry("Bramble", "Icons/ItemsGenerated/Plant_Bramble_Dry_Magic.png"),
            Map.entry("Bush", "Icons/ItemsGenerated/Plant_Bush_Green.png"),
            Map.entry("Cactus", "Icons/ItemsGenerated/Plant_Cactus_1.png"),
            Map.entry("Coral", "Icons/ItemsGenerated/Plant_Coral_Model_Yellow.png"),
            Map.entry("Crop", "Icons/ItemsGenerated/Plant_Crop_Wheat.png"),
            Map.entry("Fern", "Icons/ItemsGenerated/Plant_Fern.png"),
            Map.entry("Flower", "Icons/ItemsGenerated/Plant_Flower_Common_Allium.png"),
            Map.entry("Fruit", "Icons/ItemsGenerated/Plant_Fruit_Apple.png"),
            Map.entry("Grass", "Icons/ItemsGenerated/Plant_Grass_Tall.png"),
            Map.entry("Leaves", "Icons/ItemsGenerated/Plant_Aspen_Leaves.png"),
            Map.entry("Moss", "Icons/ItemsGenerated/Plant_Moss_Rug_Red.png"),
            Map.entry("Mushroom", "Icons/ItemsGenerated/Plant_Crop_Mushroom_Common_Brown.png"),
            Map.entry("Petals", "Icons/ItemsGenerated/Plant_Petals_Red.png"),
            Map.entry("Reeds", "Icons/ItemsGenerated/Plant_Reeds.png"),
            Map.entry("Sapling", "Icons/ItemsGenerated/Plant_Beech_Sapling.png"),
            Map.entry("Seaweed", "Icons/ItemsGenerated/Plant_Seaweed_Short.png"),
            Map.entry("Seeds", "Icons/ItemsGenerated/Plant_Seeds_Wheat.png"),
            Map.entry("Vine", "Icons/ItemsGenerated/Plant_Vine.png")
    );

    private String selectedCategory;
    private String selectedPlant;

    /**
     * Event payload sent by the client when the player interacts with the page.
     */
    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC =
                BuilderCodec.builder(PageEventData.class, PageEventData::new)
                        .append(new KeyedCodec<>("Category", Codec.STRING),
                                PageEventData::setCategory, PageEventData::getCategory).add()
                        .append(new KeyedCodec<>("Plant", Codec.STRING),
                                PageEventData::setPlant, PageEventData::getPlant).add()
                        .append(new KeyedCodec<>("Type", Codec.STRING),
                                PageEventData::setType, PageEventData::getType).add()
                        .build();

        private String category;
        private String plant;
        private String type;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getPlant() {
            return plant;
        }

        public void setPlant(String plant) {
            this.plant = plant;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public HerbariumUIPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageEventData.CODEC);
    }

    @Override
    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder commands,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        if (selectedCategory == null) {
            buildCategoryView(commands, events);
        } else {
            buildPlantView(commands, events);
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        String type = data.getType() == null ? "" : data.getType();

        switch (type) {
            case TYPE_SELECT_CATEGORY -> {
                selectedCategory = data.getCategory();
                selectedPlant = null;
                rebuild();
            }
            case TYPE_SELECT_PLANT -> {
                String previous = selectedPlant;
                selectedPlant = data.getPlant();

                PlantMemoryManager manager = Herbarium.getMemoryManager();
                List<String> plants = manager.getPlantsInCategory(selectedCategory);

                UICommandBuilder commands = new UICommandBuilder();
                if (previous != null && !previous.equals(selectedPlant)) {
                    int previousIndex = plants.indexOf(previous);
                    if (previousIndex >= 0) {
                        setTileSelection(commands, previousIndex, false);
                    }
                }
                int newIndex = plants.indexOf(selectedPlant);
                if (newIndex >= 0) {
                    setTileSelection(commands, newIndex, true);
                }
                buildDetails(commands);
                sendUpdate(commands, false);
            }
            case TYPE_BACK -> {
                selectedCategory = null;
                selectedPlant = null;
                rebuild();
            }
            case TYPE_COLLECT_REWARD -> handleCollectReward(ref, store);
            default -> {
            }
        }
    }

    /**
     * Plays the book closing sound when the player dismisses the journal.
     */
    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        SoundUtil.playSoundEvent2dToPlayer(playerRef, "SFX_Drop_Weapons_Books", SoundCategory.UI);
    }

    /**
     * Builds the category overview panel.
     */
    private void buildCategoryView(UICommandBuilder commands, UIEventBuilder events) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();
        Map<String, List<String>> categories = manager.getPlantCategories();

        commands.append("Pages/Herbarium.ui");
        commands.set("#TitleLabel.Text", Message.translation(JOURNAL_NAME_KEY));

        int totalDiscovered = 0;
        int totalPlants = 0;
        for (List<String> plants : categories.values()) {
            for (String plantId : plants) {
                if (manager.isPlantDiscovered(playerRef, plantId)) {
                    totalDiscovered++;
                }
            }
            totalPlants += plants.size();
        }

        float progress = totalPlants == 0 ? 0f : (float) totalDiscovered / totalPlants;
        commands.set("#PlantsProgressBar.Value", progress);
        commands.set("#PlantsProgressBarTexture.Value", progress);
        commands.set("#TotalCollected.Text", "plants recorded: " + totalDiscovered + " / " + totalPlants);

        buildChestMarkers(commands, totalDiscovered, totalPlants);
        setRewardsButtonState(commands, totalDiscovered, totalPlants);

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#RewardsButton",
                new EventData().append("Type", TYPE_COLLECT_REWARD)
        );

        int index = 0;
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            String category = entry.getKey();
            List<String> plants = entry.getValue();

            int discovered = 0;
            for (String plantId : plants) {
                if (manager.isPlantDiscovered(playerRef, plantId)) {
                    discovered++;
                }
            }
            boolean complete = !plants.isEmpty() && discovered == plants.size();

            commands.append("#IconList", "Pages/Herbarium/CategoryTile.ui");
            String tile = "#IconList[" + index + "]";

            commands.set(tile + " #Button.Text", category);
            commands.set(tile + " #CurrentCategoryCountNotComplete.Text", String.valueOf(discovered));
            commands.set(tile + " #CurrentCategoryCountComplete.Text", String.valueOf(discovered));
            commands.set(tile + " #TotalCategoryCountNotComplete.Text", String.valueOf(plants.size()));
            commands.set(tile + " #TotalCategoryCountComplete.Text", String.valueOf(plants.size()));

            String icon = CATEGORY_ICON_OVERRIDES.containsKey(category)
                    ? CATEGORY_ICON_OVERRIDES.get(category)
                    : findCategoryIcon(manager, plants);
            if (icon != null) {
                commands.set(tile + " #CategoryIcon.AssetPath", icon);
            } else {
                commands.setNull(tile + " #CategoryIcon.AssetPath");
            }

            if (complete) {
                commands.set(tile + " #CompleteCategoryBackground.Visible", true);
                commands.set(tile + " #CompleteCategoryCounter.Visible", true);
            } else {
                commands.set(tile + " #NotCompleteCategoryCounter.Visible", true);
            }

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    tile + " #Button",
                    new EventData()
                            .append("Category", category)
                            .append("Type", TYPE_SELECT_CATEGORY)
            );
            index++;
        }
    }

    /**
     * Adds the chest reward markers on the progress bar.
     */
    private void buildChestMarkers(UICommandBuilder commands, int totalDiscovered, int totalPlants) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();
        int tierCount = HerbariumRewards.getTierCount(totalPlants);

        for (int tier = 1; tier <= tierCount; tier++) {
            int threshold = HerbariumRewards.getTierThreshold(tier);
            boolean reached = totalDiscovered >= threshold;
            boolean rewardCollected = manager.isRewardTierCollected(playerRef, tier);
            String marker = "#ChestMarkers[" + (tier - 1) + "]";

            commands.append("#ChestMarkers", "Pages/Herbarium/ChestMarker.ui");

            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(threshold * CHEST_MARKER_SCALE / totalPlants));
            commands.setObject(marker + ".Anchor", anchor);

            String tooltipKey = rewardCollected
                    ? CHEST_COLLECTED_TOOLTIP_KEY
                    : reached ? CHEST_ACTIVE_TOOLTIP_KEY : CHEST_LOCKED_TOOLTIP_KEY;
            Color itemColor = rewardCollected ? REWARD_COLLECTED_COLOR : REWARD_ITEM_COLOR;
            Message tooltip = buildChestTooltip(tier, tooltipKey, itemColor);

            if (reached || rewardCollected) {
                String chest = rewardCollected ? "#ChestCollected" : "#ChestActive";
                commands.set(marker + " " + chest + ".Visible", true);
                commands.set(marker + " " + chest + ".TooltipTextSpans", tooltip);
                commands.set(marker + " #Arrow.Visible", reached && !rewardCollected);
            } else {
                commands.set(marker + " #ChestDisabled.Visible", true);
                commands.set(marker + " #ChestDisabled.TooltipTextSpans", tooltip);
            }
        }
    }

    /**
     * Builds the tooltip of a chest marker.
     */
    private static Message buildChestTooltip(int tier, String tooltipKey, Color itemColor) {
        return Message.translation(tooltipKey)
                .param("count", HerbariumRewards.getTierThreshold(tier))
                .insert("\n\n")
                .insert(buildTierRewardsMessage(tier, itemColor));
    }

    /**
     * Gets the lowest tier whose reward has not been collected yetd.
     */
    private int getNextUncollectedTier(int tierCount) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();
        for (int tier = 1; tier <= tierCount; tier++) {
            if (!manager.isRewardTierCollected(playerRef, tier)) {
                return tier;
            }
        }
        return 0;
    }

    private void setRewardsButtonState(UICommandBuilder commands, int totalDiscovered, int totalPlants) {
        int nextTier = getNextUncollectedTier(HerbariumRewards.getTierCount(totalPlants));

        if (nextTier == 0) {
            commands.set("#RewardsButton.Text", Message.translation(ALL_COLLECTED_KEY));
            commands.set("#RewardsButton.Disabled", true);
        } else {
            commands.set("#RewardsButton.Text",
                    Message.translation(COLLECT_BUTTON_KEY).param("tier", nextTier));
            commands.set("#RewardsButton.Disabled",
                    totalDiscovered < HerbariumRewards.getTierThreshold(nextTier));
        }
    }

    private void handleCollectReward(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return;
        }

        PlantMemoryManager manager = Herbarium.getMemoryManager();
        int totalPlants = manager.getTotalPlantCount();
        int totalDiscovered = manager.getMemoryCount(playerRef);
        int tier = getNextUncollectedTier(HerbariumRewards.getTierCount(totalPlants));

        // Nothing to collect, or the next uncollected tier has not been reached yet
        if (tier == 0 || totalDiscovered < HerbariumRewards.getTierThreshold(tier)) {
            return;
        }

        manager.markRewardTierCollected(playerRef, tier);

        List<ItemStack> rewards = new ArrayList<>();
        for (HerbariumRewards.Reward reward : HerbariumRewards.getTierRewards(tier)) {
            rewards.add(new ItemStack(reward.itemId(), reward.quantity()));
        }
        SimpleItemContainer.addOrDropItemStacks(store, ref,
                InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST), rewards);

        SoundUtil.playSoundEvent2dToPlayer(playerRef, COLLECT_SOUND_EVENT, SoundCategory.SFX);

        UICommandBuilder commands = new UICommandBuilder();

        String marker = "#ChestMarkers[" + (tier - 1) + "]";
        commands.set(marker + " #ChestActive.Visible", false);
        commands.set(marker + " #ChestCollected.Visible", true);
        commands.set(marker + " #Arrow.Visible", false);
        commands.set(marker + " #ChestCollected.TooltipTextSpans",
                buildChestTooltip(tier, CHEST_COLLECTED_TOOLTIP_KEY, REWARD_COLLECTED_COLOR));

        setRewardsButtonState(commands, totalDiscovered, totalPlants);

        sendUpdate(commands, false);
    }

    private static Message buildTierRewardsMessage(int tier, Color itemColor) {
        Message list = Message.empty();

        boolean first = true;
        for (HerbariumRewards.Reward reward : HerbariumRewards.getTierRewards(tier)) {
            if (!first) {
                list = list.insert("\n");
            }
            first = false;

            list = list.insert(Message.join(
                    Message.raw(reward.quantity() + "x ").color(REWARD_QUANTITY_COLOR),
                    rewardNameMessage(reward.itemId()).color(itemColor)
            ));
        }
        return list;
    }

    private static Message rewardNameMessage(String itemId) {
        return Message.translation("server.items." + itemId + ".name");
    }

    /**
     * Builds the plant list panel for the selected category.
     */
    private void buildPlantView(UICommandBuilder commands, UIEventBuilder events) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();
        List<String> plants = manager.getPlantsInCategory(selectedCategory);

        commands.append("Pages/Herbarium/PlantPanel.ui");
        commands.set("#TitleLabel.Text", Message.translation(JOURNAL_NAME_KEY));

        int discovered = 0;
        for (String plantId : plants) {
            if (manager.isPlantDiscovered(playerRef, plantId)) {
                discovered++;
            }
        }
        commands.set("#CategoryTitle.Text", selectedCategory);
        commands.set("#CategoryCount.Text", discovered + "/" + plants.size());

        int index = 0;
        for (String plantId : plants) {
            commands.append("#IconList", "Pages/Herbarium/PlantTile.ui");
            String tile = "#IconList[" + index + "]";

            boolean isDiscovered = manager.isPlantDiscovered(playerRef, plantId);
            boolean isSelected = plantId.equals(selectedPlant);

            if (isDiscovered) {
                String button = isSelected ? "#ButtonSelected" : "#ButtonNotSelected";
                commands.set(tile + " " + button + ".Visible", true);
                setPlantName(commands, tile + " " + button + ".TooltipText", plantId);

                String icon = manager.getPlantIcon(plantId);
                if (icon != null) {
                    commands.set(tile + " #Icon.AssetPath", icon);
                } else {
                    commands.setNull(tile + " #Icon.AssetPath");
                }
            } else {
                commands.set(tile + " #EmptyBackground.Visible", true);
                commands.setNull(tile + " #Icon.AssetPath");
            }

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    tile + " #ButtonNotSelected",
                    new EventData()
                            .append("Plant", plantId)
                            .append("Type", TYPE_SELECT_PLANT)
            );
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    tile + " #ButtonSelected",
                    new EventData()
                            .append("Plant", plantId)
                            .append("Type", TYPE_SELECT_PLANT)
            );
            index++;
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#BackButton",
                new EventData().append("Type", TYPE_BACK)
        );

        buildDetails(commands);
    }

    /**
     * Toggles the selected/not-selected buttons of a plant tile.
     */
    private void setTileSelection(UICommandBuilder commands, int index, boolean selected) {
        String tile = "#IconList[" + index + "]";
        commands.set(tile + " #ButtonSelected.Visible", selected);
        commands.set(tile + " #ButtonNotSelected.Visible", !selected);
    }

    /**
     * Fills the right-side details panel with the selected plant.
     */
    private void buildDetails(UICommandBuilder commands) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();

        if (selectedPlant == null) {
            commands.set("#PlantName.Text", "");
            setLocationsPanel(commands, null);
            commands.set("#PlantDiscovery.Text", "");
            commands.setNull("#PlantIcon.AssetPath");
            return;
        }

        setPlantName(commands, "#PlantName.Text", selectedPlant);

        setLocationsPanel(commands, manager.getPlantLocations(selectedPlant));

        PlantInfo plantInfo = manager.getPlayerPlantInfo(playerRef, selectedPlant);
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

        String icon = manager.getPlantIcon(selectedPlant);
        if (icon != null) {
            commands.set("#PlantIcon.AssetPath", icon);
        } else {
            commands.setNull("#PlantIcon.AssetPath");
        }
    }

    /**
     * Shows the plant locations in the details panel. A text property takes a
     * single translation message only, so each location key gets its own
     * label. A null list means no plant is selected: everything is hidden.
     * An empty list means the selected plant has no known location: the
     * header and the "location unknown" fallback are shown.
     */
    private void setLocationsPanel(UICommandBuilder commands, List<String> locationKeys) {
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

    /**
     * Formats an ISO-8601 instant as a plain date (yyyy-MM-dd).
     */
    private String formatDate(String isoInstant) {
        int separatorIndex = isoInstant.indexOf('T');
        return separatorIndex > 0 ? isoInstant.substring(0, separatorIndex) : isoInstant;
    }

    /**
     * Sets a plant display name on a text property. Raw messages cannot be
     * assigned to text markup properties, so untranslated plants use a plain
     * string and translated plants use a translation message.
     */
    private void setPlantName(UICommandBuilder commands, String selector, String plantId) {
        PlantMemoryManager manager = Herbarium.getMemoryManager();
        String translationKey = manager.getPlantTranslationKey(plantId);
        if (translationKey != null) {
            commands.set(selector, Message.translation(translationKey));
        } else {
            commands.set(selector, plantId);
        }
    }

    /**
     * Finds a representative icon for a category (first plant with an icon).
     */
    private String findCategoryIcon(PlantMemoryManager manager, List<String> plants) {
        for (String plantId : plants) {
            String icon = manager.getPlantIcon(plantId);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }
}
