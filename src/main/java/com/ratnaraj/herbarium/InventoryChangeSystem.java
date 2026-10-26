package com.ratnaraj.herbarium;

import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import com.hypixel.hytale.protocol.SoundCategory;

import org.joml.Vector3d;

import java.awt.Color;
import java.time.Instant;
import java.util.logging.Level;

/**
 * ECS system to detect inventory changes.
 * Sends a message to the player for pickup and take_from_container.
 * Saves plant memories when the player picks a plant.
 */
public class InventoryChangeSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    public InventoryChangeSystem() {
        super(InventoryChangeEvent.class);
    }

    /**
     * Gets the player's block position as "x, y, z", or null if unavailable.
     */
    private String getPlayerPosition(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        try {
            TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
            if (transform != null) {
                Vector3d pos = transform.getPosition();
                if (pos != null) {
                    return Math.round(pos.x) + ", " + Math.round(pos.y) + ", " + Math.round(pos.z);
                }
            }
        } catch (Exception e) {
            Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                    "[Herbarium] Unable to read player position: " + e.getMessage()
            );
        }
        return null;
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

        // Determine the action type and the relevant item
        Transaction transaction = event.getTransaction();
        String actionType = null;
        ItemStack itemStack = null;

        if (transaction instanceof ItemStackTransaction) {
            ItemStackTransaction itemTrans = (ItemStackTransaction) transaction;
            ActionType action = itemTrans.getAction();
            if (action.isAdd()) {
                actionType = "pickup";
                itemStack = itemTrans.getQuery();
            }
        } else if (transaction instanceof MoveTransaction) {
            MoveTransaction moveTrans = (MoveTransaction) transaction;
            MoveType moveType = moveTrans.getMoveType();
            if (moveType == MoveType.MOVE_TO_SELF) {
                actionType = "take_from_container";
                Transaction addTrans = moveTrans.getAddTransaction();
                if (addTrans instanceof ItemStackTransaction) {
                    itemStack = ((ItemStackTransaction) addTrans).getQuery();
                }
            }
        }

        // If the action is not interesting, exit
        if (actionType == null) {
            return;
        }

        // Get the item id and name
        String itemId = "unknown";
        String itemName = null;

        if (itemStack != null && !itemStack.isEmpty()) {
            itemId = itemStack.getItemId();
            itemName = itemStack.getDisplayName().getRawText();
        }

        // Check if the item is a plant
        boolean isPlant = Herbarium.getMemoryManager().isPlantItem(itemId);

        if (!isPlant) { return; }

        // Iterate through all entities in the chunk to find the player
        for (int i = 0; i < chunk.size(); i++) {
            Ref<EntityStore> entityRef = chunk.getReferenceTo(i);

                try {
                    PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
                    if (playerRef != null && playerRef.isValid()) {
                        // Discovery progression stays inactive until the
                        // player has opened the journal for the first time.
                        if (!Herbarium.getMemoryManager().hasOpenedJournal(playerRef)) {
                            return;
                        }

                        String discoveredDate = Instant.now().toString();
                        String discoveredPlace = getPlayerPosition(store, entityRef);
                        boolean isNewDiscovery = Herbarium.getMemoryManager().addPlantMemory(
                                playerRef, itemId, itemName, discoveredDate, discoveredPlace);

                        if (isNewDiscovery) {
                            NotificationUtil.sendNotification(
                                    playerRef.getPacketHandler(),
                                    Message.join(
                                            Message.raw("New plant discovered: "),
                                            Herbarium.getMemoryManager().getPlantNameMessage(itemId).color(Color.YELLOW)
                                    ),
                                    null,
                                    "NotificationIcons/PlantIcon.png"
                            );

                            SoundUtil.playSoundEvent2dToPlayer(
                                    playerRef,
                                    "SFX_MemoryMote",
                                    SoundCategory.SFX
                            );
                        }

                        return;
                    }
                } catch (Exception e) {
                    // Ignore errors
                    Herbarium.getInstance().getLogger().at(Level.WARNING).log(
                            "[Herbarium] Error during processing: " + e.getMessage()
                    );
                }

        }
    }
}
