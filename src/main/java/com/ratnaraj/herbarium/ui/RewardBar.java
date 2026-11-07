package com.ratnaraj.herbarium.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.HerbariumRewards;
import com.ratnaraj.herbarium.data.PlayerStore;
import com.ratnaraj.herbarium.event.FeedbackSound;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RewardBar {

    private static final int CHEST_MARKER_SCALE = 1000;
    private static final String CHEST_LOCKED_TOOLTIP_KEY = "server.herbarium.rewards.chestLocked.tooltipText";
    private static final String CHEST_ACTIVE_TOOLTIP_KEY = "server.herbarium.rewards.chestActive.tooltipText";
    private static final String CHEST_COLLECTED_TOOLTIP_KEY = "server.herbarium.rewards.chestCollected.tooltipText";

    private static final Color REWARD_QUANTITY_COLOR = new Color(0x88, 0x88, 0x88);
    private static final Color REWARD_ITEM_COLOR = Color.WHITE;
    private static final Color REWARD_COLLECTED_COLOR = new Color(0x88, 0x88, 0x88);

    private static final String COLLECT_BUTTON_KEY = "server.herbarium.rewards.collectButton";
    private static final String ALL_COLLECTED_KEY = "server.herbarium.rewards.allCollected";
    private static final String COLLECT_SOUND_EVENT = "SFX_MemoryRestored";

    public static void buildChestMarkers(PlayerRef playerRef, UICommandBuilder commands, int totalDiscovered, int totalPlants) {
        PlayerStore store = Herbarium.getPlayerStore();
        int tierCount = HerbariumRewards.getTierCount(totalPlants);

        for (int tier = 1; tier <= tierCount; tier++) {
            int threshold = HerbariumRewards.getTierThreshold(tier);
            boolean reached = totalDiscovered >= threshold;
            boolean rewardCollected = store.isRewardTierCollected(playerRef, tier);
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

    public static Message buildChestTooltip(int tier, String tooltipKey, Color itemColor) {
        return Message.translation(tooltipKey)
                .param("count", HerbariumRewards.getTierThreshold(tier))
                .insert("\n\n")
                .insert(buildTierRewardsMessage(tier, itemColor));
    }

    public static Message buildTierRewardsMessage(int tier, Color itemColor) {
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

    public static Message rewardNameMessage(String itemId) {
        return Message.translation("server.items." + itemId + ".name");
    }

    public static void setRewardsButtonState(PlayerRef playerRef, UICommandBuilder commands, int totalDiscovered, int totalPlants) {
        int nextTier = getNextUncollectedTier(playerRef, HerbariumRewards.getTierCount(totalPlants));

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

    public static int getNextUncollectedTier(PlayerRef playerRef, int tierCount) {
        for (int tier = 1; tier <= tierCount; tier++) {
            if (!Herbarium.getPlayerStore().isRewardTierCollected(playerRef, tier)) {
                return tier;
            }
        }

        return 0;
    }

    public static UICommandBuilder handleCollectReward(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return null;
        }

        PlayerStore playerStore = Herbarium.getPlayerStore();
        int totalPlants = Herbarium.getCatalog().getTotalPlantCount();
        int totalDiscovered = playerStore.getMemoryCount(playerRef);
        int tier = getNextUncollectedTier(playerRef, HerbariumRewards.getTierCount(totalPlants));

        if (tier == 0 || totalDiscovered < HerbariumRewards.getTierThreshold(tier)) {
            return null;
        }

        playerStore.markRewardTierCollected(playerRef, tier);

        List<ItemStack> rewards = new ArrayList<>();

        for (HerbariumRewards.Reward reward : HerbariumRewards.getTierRewards(tier)) {
            rewards.add(new ItemStack(reward.itemId(), reward.quantity()));
        }

        SimpleItemContainer.addOrDropItemStacks(store, ref,
                InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST), rewards);

        FeedbackSound.play(playerRef, COLLECT_SOUND_EVENT, store, true);

        UICommandBuilder commands = new UICommandBuilder();

        String marker = "#ChestMarkers[" + (tier - 1) + "]";
        commands.set(marker + " #ChestActive.Visible", false);
        commands.set(marker + " #ChestCollected.Visible", true);
        commands.set(marker + " #Arrow.Visible", false);
        commands.set(marker + " #ChestCollected.TooltipTextSpans",
                buildChestTooltip(tier, CHEST_COLLECTED_TOOLTIP_KEY, REWARD_COLLECTED_COLOR));

        setRewardsButtonState(playerRef, commands, totalDiscovered, totalPlants);

        return commands;
    }

}
