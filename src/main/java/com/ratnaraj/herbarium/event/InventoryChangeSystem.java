package com.ratnaraj.herbarium.event;

import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import org.joml.Vector3d;

import com.ratnaraj.herbarium.Herbarium;
import com.ratnaraj.herbarium.data.PlantAssets;

import java.awt.Color;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Records plant discoveries when a player picks up a plant item, on pickup
 * or when taking it from a container.
 */
public class InventoryChangeSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final String DISCOVERY_NOTIFICATION_KEY = "server.herbarium.notification.discovered";

    private final String DISCOVERY_SOUND_EVENT = "SFX_MemoryMote";

    public InventoryChangeSystem() {
        super(InventoryChangeEvent.class);
    }

    private String getPlayerPosition(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        try {
            TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
            if (transform != null) {
                Vector3d pos = transform.getPosition();
                return Math.round(pos.x) + ", " + Math.round(pos.y) + ", " + Math.round(pos.z);
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Unable to read player position: " + e.getMessage()
            );
        }
        return null;
    }

    private ItemStack findAddedStack(Transaction transaction) {
        if (transaction instanceof ItemStackTransaction itemTrans) {
            return itemTrans.getAction().isAdd() ? itemTrans.getQuery() : null;
        }

        if (transaction instanceof MoveTransaction moveTrans) {
            if (moveTrans.getMoveType() != MoveType.MOVE_TO_SELF) {
                return null;
            }

            Transaction addTrans = moveTrans.getAddTransaction();

            if (addTrans instanceof ItemStackTransaction itemAddTrans) {
                return itemAddTrans.getQuery();
            }

            if (addTrans instanceof SlotTransaction) {
                return getAddedSlotContent((SlotTransaction) addTrans);
            }

            return null;
        }

        if (transaction instanceof SlotTransaction slotTrans) {
            // Slot-level drops: the dropped stack becomes the slot's new
            // content. Rearranging plants between the player's own slots is
            // harmless, addPlantMemory ignores rediscoveries.
            if (slotTrans.getAction() == ActionType.SET
                    || slotTrans.getAction() == ActionType.REPLACE
                    || slotTrans.getAction().isAdd()) {
                return getAddedSlotContent(slotTrans);
            }
            return null;
        }

        if (transaction instanceof ListTransaction<?> listTrans) {
            for (Transaction child : listTrans.getList()) {
                ItemStack stack = findAddedStack(child);
                if (stack != null) {
                    return stack;
                }
            }
        }

        return null;
    }

    /**
     * The content a slot transaction puts into its slot: the added stack
     * when it is a stack-level add (drag &amp; drop), else the slot's new
     * content.
     */
    private ItemStack getAddedSlotContent(SlotTransaction slotTrans) {
        if (slotTrans instanceof ItemStackSlotTransaction stackSlotTrans
                && stackSlotTrans.getQuery() != null) {
            return stackSlotTrans.getQuery();
        }
        return slotTrans.getSlotAfter();
    }

    @Override
    public Class<InventoryChangeEvent> getEventType() {
        return InventoryChangeEvent.class;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int chunkX, ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                       InventoryChangeEvent event) {

        ItemStack itemStack = findAddedStack(event.getTransaction());

        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }

        String itemId = itemStack.getItemId();
        String itemName = itemStack.getDisplayName().getRawText();

        if (!Herbarium.getCatalog().isPlantItem(itemId)) { return; }

        // The event targets one entity's inventory component: only the chunk
        // entity owning that component is the one that picked the plant up.
        // Crediting the first player found in the chunk would attribute the
        // discovery to the wrong player when several players are nearby.
        for (int i = 0; i < chunk.size(); i++) {
            if (chunk.getComponent(i, event.getComponentType()) != event.getInventory()) {
                continue;
            }

            Ref<EntityStore> entityRef = chunk.getReferenceTo(i);

            try {
                PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
                if (playerRef == null || !playerRef.isValid()) {
                    return;
                }

                // Discovery progression stays inactive until the
                // player has opened the journal for the first time.
                if (!Herbarium.getPlayerStore().hasOpenedJournal(playerRef)) {
                    return;
                }

                String discoveredDate = Instant.now().toString();
                String discoveredPlace = getPlayerPosition(store, entityRef);
                boolean isNewDiscovery = Herbarium.getPlayerStore().addPlantMemory(
                        playerRef, itemId, itemName, discoveredDate, discoveredPlace);

                if (isNewDiscovery) {
                    NotificationUtil.sendNotification(
                            playerRef.getPacketHandler(),
                            Message.translation(DISCOVERY_NOTIFICATION_KEY)
                                    .insert(" ")
                                    .insert(PlantAssets.getPlantNameMessage(itemId).color(Color.YELLOW)),
                            null,
                            "NotificationIcons/PlantIcon.png"
                    );

                    FeedbackSound.play(playerRef, DISCOVERY_SOUND_EVENT, store, false);
                }

                return;
            } catch (Exception e) {
                Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                        "[Herbarium] Error during processing: " + e.getMessage()
                );
                return;
            }
        }
    }
}
