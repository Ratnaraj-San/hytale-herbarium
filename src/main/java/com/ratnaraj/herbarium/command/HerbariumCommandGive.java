package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class HerbariumCommandGive extends AbstractPlayerCommand {

    private static final String GIVE_DESC_KEY = "server.herbarium.command.give.desc";
    private static final String GIVE_DONE_KEY = "server.herbarium.command.give.done";
    private static final String JOURNAL_NAME_KEY = "server.items.Herbarium_Journal.name";

    public HerbariumCommandGive() {
        super("give", GIVE_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "give"));
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        CombinedItemContainer container = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
        ItemStack journal = new ItemStack("Herbarium_Journal", 1);

        SimpleItemContainer.addOrDropItemStack(store, ref, container, journal);

        ctx.sendMessage(Message
                .translation(GIVE_DONE_KEY)
                .param("journal", Message.translation(JOURNAL_NAME_KEY))
                .param("player", playerRef.getUsername())
        );
    }

}
