package com.ratnaraj.herbarium.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
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
    private static final String PLAYER_ARG_DESC_KEY = "server.herbarium.command.common.player.desc";
    private static final String PLAYER_NOT_IN_WORLD_KEY = "server.commands.errors.playerNotInWorld";

    private final OptionalArg<PlayerRef> playerArg;

    public HerbariumCommandGive() {
        super("give", GIVE_DESC_KEY);
        requirePermission(HytalePermissions.fromCommand("herbarium", "give"));
        playerArg = withOptionalArg("player", PLAYER_ARG_DESC_KEY, ArgTypes.PLAYER_REF);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        PlayerRef target = ctx.provided(playerArg) ? playerArg.get(ctx) : playerRef;

        Ref<EntityStore> targetRef = target.getReference();

        if (!targetRef.isValid()) {
            ctx.sendMessage(Message.translation(PLAYER_NOT_IN_WORLD_KEY));
            return;
        }

        Store<EntityStore> targetStore = targetRef.getStore();
        World targetWorld = targetStore.getExternalData().getWorld();

        // The target may be in another world: inventory access must run on that world's thread.
        targetWorld.execute(() -> {
            CombinedItemContainer container = InventoryComponent.getCombined(targetStore, targetRef, InventoryComponent.HOTBAR_FIRST);
            ItemStack journal = new ItemStack("Herbarium_Journal", 1);

            SimpleItemContainer.addOrDropItemStack(targetStore, targetRef, container, journal);

            ctx.sendMessage(Message
                    .translation(GIVE_DONE_KEY)
                    .param("journal", Message.translation(JOURNAL_NAME_KEY))
                    .param("player", target.getUsername())
            );
        });
    }
}
