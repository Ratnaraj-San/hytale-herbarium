package com.ratnaraj.herbarium.ui;

import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantAssets;
import com.ratnaraj.herbarium.data.PlantInfo;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ratnaraj.herbarium.event.FeedbackSound;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Botanical journal shown by /herbarium ui.
 * Mirrors the vanilla Memories page flow: a category overview panel and a
 * per-category plant grid with a details panel, swapped through rebuild().
 */
public class HerbariumUIPage extends InteractiveCustomUIPage<HerbariumUIPage.PageEventData> {

    private static final String CLOSE_SOUND = "SFX_Drop_Weapons_Books";

    private static final String TYPE_SELECT_CATEGORY = "SelectCategory";
    private static final String TYPE_SELECT_PLANT = PlantDetailsPanel.TYPE_SELECT_PLANT;
    private static final String TYPE_BACK = "Back";
    private static final String TYPE_COLLECT_REWARD = "CollectReward";
    private static final String TYPE_SHOW_INFO = "ShowInfo";
    private static final String TYPE_TOGGLE_COLLAPSE = "ToggleCollapse";

    /** Containers of the plant details panel that can be collapsed via #CollapseButton. */
    private static final List<String> COLLAPSIBLE_CONTAINERS =
            List.of("PlantContainerLocations", "PlantContainerObtainedFrom", "PlantContainerGives");

    // Vanilla arrows: down = expanded, right = collapsed.
    private static final String ARROW_EXPANDED = "UI/Custom/Common/ColorPickerDropdownBoxArrow@2x.png";
    private static final String ARROW_COLLAPSED = "UI/Custom/Common/ContainerTitleArrow@2x.png";
    
    private static final String JOURNAL_NAME_KEY = "server.items.Herbarium_Journal.name";
    private static final String JOURNAL_DEV_NAME_KEY = "server.items.Herbarium_Journal_Dev.name";
    private static final String TOTAL_COLLECTED_KEY = "server.herbarium.ui.journal.progress";
    private static final String CATEGORY_KEY_PREFIX = "server.herbarium.ui.category.";

    private static final Map<String, String> CATEGORY_ICON_OVERRIDES = Map.ofEntries(
            Map.entry("Bramble", "Icons/ItemsGenerated/Plant_Bramble_Dry.png"),
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

    /** Ids of the collapsible containers currently collapsed (per open page instance). */
    private final Set<String> collapsedContainers = new HashSet<>();

    private final boolean devView;

    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC =
                BuilderCodec.builder(PageEventData.class, PageEventData::new)
                        .append(new KeyedCodec<>("Category", Codec.STRING),
                                PageEventData::setCategory, PageEventData::getCategory).add()
                        .append(new KeyedCodec<>("Plant", Codec.STRING),
                                PageEventData::setPlant, PageEventData::getPlant).add()
                        .append(new KeyedCodec<>("Container", Codec.STRING),
                                PageEventData::setContainer, PageEventData::getContainer).add()
                        .append(new KeyedCodec<>("Type", Codec.STRING),
                                PageEventData::setType, PageEventData::getType).add()
                        .build();

        private String category;
        private String plant;
        private String container;
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

        public String getContainer() {
            return container;
        }

        public void setContainer(String container) {
            this.container = container;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /** Whose journal data is displayed; the page itself belongs to playerRef (the viewer). */
    private final PlayerRef subjectRef;

    public static HerbariumUIPage devJournal(PlayerRef viewer) {
        return new HerbariumUIPage(viewer, viewer, true);
    }
    
    public HerbariumUIPage(PlayerRef subject, PlayerRef viewer, boolean devView) {
        super(viewer, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEventData.CODEC);
        this.subjectRef = subject;
        this.devView = devView;
    }

    private boolean isOwnJournal() {
        return playerRef.getUuid().equals(subjectRef.getUuid());
    }

    @Override
    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder commands,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        if (selectedCategory == null) {
            buildCategoryView(commands, events, ref);
        } else {
            buildPlantView(commands, events, ref);
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

                List<String> plants = Herbarium.getCatalog().getPlantsInCategory(selectedCategory);

                UICommandBuilder commands = new UICommandBuilder();
                UIEventBuilder events = new UIEventBuilder();
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
                PlantDetailsPanel.buildDetails(subjectRef, commands, events, selectedPlant, devView);
                applyCollapseState(commands, events);
                sendUpdate(commands, events, false);
            }
            case TYPE_TOGGLE_COLLAPSE -> {
                String container = data.getContainer();
                if (container != null && COLLAPSIBLE_CONTAINERS.contains(container)) {
                    if (!collapsedContainers.remove(container)) {
                        collapsedContainers.add(container);
                    }
                    UICommandBuilder commands = new UICommandBuilder();
                    setContainerCollapsed(commands, container, collapsedContainers.contains(container));
                    sendUpdate(commands, false);
                }
            }
            case TYPE_BACK -> {
                selectedCategory = null;
                selectedPlant = null;
                rebuild();
            }
            case TYPE_COLLECT_REWARD -> {
                // Rewards can only be collected on one's own journal.
                if (!devView && isOwnJournal()) {
                    UICommandBuilder update = RewardBar.handleCollectReward(subjectRef, ref, store);

                    if (update != null) {
                        sendUpdate(update, false);
                    }
                }
            }
            case TYPE_SHOW_INFO -> handleShowInfo(ref, store);
            default -> {
            }
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        FeedbackSound.play(playerRef, CLOSE_SOUND, store, true);
    }
    
    private Message buildTitle() {
        if (devView) { return Message.translation(JOURNAL_DEV_NAME_KEY); }
        return Message.translation(JOURNAL_NAME_KEY);
    }

    private void buildCategoryView(UICommandBuilder commands, UIEventBuilder events, Ref<EntityStore> ref) {
        Map<String, List<String>> categories = Herbarium.getCatalog().getPlantCategories();
        Set<String> discoveredPlants = getDiscoveredPlantIds();

        commands.append("Pages/Herbarium.ui");
        commands.set("#TitleLabel.Text", Message.translation(JOURNAL_NAME_KEY));

        int totalDiscovered = 0;
        int totalPlants = 0;
        for (List<String> plants : categories.values()) {
            for (String plantId : plants) {
                if (discoveredPlants.contains(plantId)) {
                    totalDiscovered++;
                }
            }
            totalPlants += plants.size();
        }

        float progress = totalPlants == 0 ? 0f : (float) totalDiscovered / totalPlants;
        commands.set("#PlantsProgressBar.Value", progress);
        commands.set("#PlantsProgressBarTexture.Value", progress);
        commands.set("#TotalCollected.Text",
                Message.translation(TOTAL_COLLECTED_KEY)
                        .param("count", totalDiscovered)
                        .param("total", totalPlants));

        if (devView) {
            commands.set("#ChestMarkers.Visible", false);
            commands.set("#RewardsButton.Visible", false);
        } else {
            RewardBar.buildChestMarkers(subjectRef, commands, totalDiscovered, totalPlants);
            RewardBar.setRewardsButtonState(subjectRef, commands, totalDiscovered, totalPlants);

            if (!isOwnJournal()) {
                commands.set("#RewardsButton.Disabled", true);
            }

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#RewardsButton",
                    new EventData().append("Type", TYPE_COLLECT_REWARD)
            );
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#InfoButton",
                new EventData().append("Type", TYPE_SHOW_INFO)
        );

        int index = 0;
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            String category = entry.getKey();
            List<String> plants = entry.getValue();

            int discovered = 0;
            for (String plantId : plants) {
                if (discoveredPlants.contains(plantId)) {
                    discovered++;
                }
            }
            boolean complete = !plants.isEmpty() && discovered == plants.size();

            commands.append("#IconList", "Pages/Herbarium/components/CategoryTile.ui");
            String tile = "#IconList[" + index + "]";

            commands.set(tile + " #Button.Text",
                    Message.translation(CATEGORY_KEY_PREFIX + category.toLowerCase(Locale.ROOT)));
            commands.set(tile + " #CurrentCategoryCountNotComplete.Text", String.valueOf(discovered));
            commands.set(tile + " #CurrentCategoryCountComplete.Text", String.valueOf(discovered));
            commands.set(tile + " #TotalCategoryCountNotComplete.Text", String.valueOf(plants.size()));
            commands.set(tile + " #TotalCategoryCountComplete.Text", String.valueOf(plants.size()));

            String icon = CATEGORY_ICON_OVERRIDES.containsKey(category)
                    ? CATEGORY_ICON_OVERRIDES.get(category)
                    : findCategoryIcon(plants);
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

    private void handleShowInfo(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());

        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new HerbariumIntroPage(subjectRef, playerRef));
        }
    }

    private void buildPlantView(UICommandBuilder commands, UIEventBuilder events, Ref<EntityStore> ref) {
        List<String> plants = Herbarium.getCatalog().getPlantsInCategory(selectedCategory);
        Set<String> discoveredPlants = getDiscoveredPlantIds();

        commands.append("Pages/Herbarium/PlantPanel.ui");
        commands.set("#TitleLabel.Text", Message.translation(JOURNAL_NAME_KEY));

        int discovered = 0;
        for (String plantId : plants) {
            if (discoveredPlants.contains(plantId)) {
                discovered++;
            }
        }
        commands.set("#CategoryTitle.Text",
                Message.translation(CATEGORY_KEY_PREFIX + selectedCategory.toLowerCase(Locale.ROOT)));
        commands.set("#CategoryCount.Text", discovered + "/" + plants.size());

        int index = 0;
        for (String plantId : plants) {
            commands.append("#IconList", "Pages/Herbarium/components/PlantTile.ui");
            String tile = "#IconList[" + index + "]";

            boolean isDiscovered = discoveredPlants.contains(plantId);
            boolean isSelected = plantId.equals(selectedPlant);

            if (isDiscovered) {
                String button = isSelected ? "#ButtonSelected" : "#ButtonNotSelected";
                commands.set(tile + " " + button + ".Visible", true);
                PlantDetailsPanel.setPlantName(commands, tile + " " + button + ".TooltipTextSpans", plantId);

                String icon = PlantAssets.getPlantIcon(plantId);
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

        PlantDetailsPanel.buildDetails(subjectRef, commands, events, selectedPlant, devView);
        applyCollapseState(commands, events);
    }

    /**
     * Re-applies the collapsed state of every collapsible container and (re)binds
     * their #CollapseButton. Called on every plant-view update so the bindings stay
     * attached after the panel is rebuilt.
     */
    private void applyCollapseState(UICommandBuilder commands, UIEventBuilder events) {
        for (String container : COLLAPSIBLE_CONTAINERS) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + container + " #CollapseButton",
                    new EventData()
                            .append("Type", TYPE_TOGGLE_COLLAPSE)
                            .append("Container", container)
            );
            setContainerCollapsed(commands, container, collapsedContainers.contains(container));
        }
    }

    private static void setContainerCollapsed(UICommandBuilder commands, String container, boolean collapsed) {
        String selector = "#" + container;
        commands.set(selector + " #ContainerBody.Visible", !collapsed);
        commands.set(selector + " #CollapseArrow.AssetPath", collapsed ? ARROW_COLLAPSED : ARROW_EXPANDED);
    }

    private void setTileSelection(UICommandBuilder commands, int index, boolean selected) {
        String tile = "#IconList[" + index + "]";
        commands.set(tile + " #ButtonSelected.Visible", selected);
        commands.set(tile + " #ButtonNotSelected.Visible", !selected);
    }

    /**
     * Ids of the plants this player discovered, as a set for cheap
     * membership checks while building the views.
     */
    private Set<String> getDiscoveredPlantIds() {
        if (devView) {
            return Herbarium.getCatalog().getAllPlants().keySet();
        }
        
        Set<String> discovered = new HashSet<>();

        for (PlantInfo info : Herbarium.getPlayerStore().getPlayerMemories(subjectRef)) {
            if (info.wasDiscovered()) {
                discovered.add(info.getId());
            }
        }

        return discovered;
    }

    private String findCategoryIcon(List<String> plants) {
        for (String plantId : plants) {
            String icon = PlantAssets.getPlantIcon(plantId);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }
}
